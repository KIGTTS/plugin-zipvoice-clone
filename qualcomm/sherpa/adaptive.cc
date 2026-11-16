// SPDX-License-Identifier: MIT
#include "adaptive.h"
#include "sherpa-onnx/csrc/normal-data-generator.h"
#include <jni.h>
#include <dlfcn.h>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <algorithm>
#include <mutex>
#include <sstream>
#include <iomanip>
#include <unordered_map>
#include <stdexcept>
#include <fstream>
#include <cstdio>
#include <nlohmann/json.hpp>

namespace kig {
using Clock = std::chrono::steady_clock;
static double Millis(Clock::time_point t) { return std::chrono::duration<double,std::milli>(Clock::now()-t).count(); }
static std::string Quote(const std::string& s) { std::ostringstream o;o<<'"';for(unsigned char c:s){if(c=='"'||c=='\\')o<<'\\'<<c;else if(c<32)o<<"\\u"<<std::hex<<std::setw(4)<<std::setfill('0')<<int(c)<<std::dec;else o<<c;}o<<'"';return o.str(); }
static std::string Utf8(JNIEnv* env,jstring s) {const char* p=env->GetStringUTFChars(s,nullptr);if(!p)throw std::runtime_error("String allocation failed");std::string r(p);env->ReleaseStringUTFChars(s,p);return r;}
struct Request {
  std::string mode="cpu",libs,skels,fault,profiles;
  JNIEnv* jni=nullptr;
  jobject guard=nullptr;
  jmethodID check=nullptr;
  std::vector<std::string> events;
  int32_t seed=-1;
  bool trace_seed=false;
  std::unique_ptr<sherpa_onnx::NormalDataGenerator> noise;
  std::vector<std::string> noise_hashes, mel_hashes;
};
static thread_local Request request;

static std::string HashFloatInput(const float* data,size_t count) {
  // Bounded, stable same-device fingerprint for diagnostic equality checks.
  uint64_t hash=14695981039346656037ULL;
  const auto* bytes=reinterpret_cast<const unsigned char*>(data);
  for(size_t i=0;i<count*sizeof(float);++i){
    hash^=bytes[i];
    hash*=1099511628211ULL;
  }
  std::ostringstream out;
  out<<std::hex<<std::setfill('0')<<std::setw(16)<<hash;
  return out.str();
}
bool FillProbeNoise(float* data,size_t count) {
  if(!request.noise)return false;
  request.noise->Fill(data,count);
  if(request.trace_seed)request.noise_hashes.push_back(HashFloatInput(data,count));
  return true;
}
bool Active() {return !request.guard || (!request.jni->ExceptionCheck() && request.jni->CallBooleanMethod(request.guard,request.check));}
static Ort::Env qnn_env(ORT_LOGGING_LEVEL_WARNING,"kig-zip-qnn");
static std::mutex registration_gate;
static bool registered=false;

// Compare complex spectra, not raw phase where the magnitude is near zero.
static double VocosRelativeError(const std::vector<Ort::Value>& values,
                                 const std::vector<Ort::Value>& reference) {
  if (values.size()!=3 || reference.size()!=3) throw std::runtime_error("Invalid Vocos outputs");
  double delta=0,energy=0;
  const size_t n=values[0].GetTensorTypeAndShapeInfo().GetElementCount();
  const float* magnitude=values[0].GetTensorData<float>();
  const float* ref_magnitude=reference[0].GetTensorData<float>();
  for (size_t j=0;j<3;++j) {
    if(values[j].GetTensorTypeAndShapeInfo().GetShape()!=reference[j].GetTensorTypeAndShapeInfo().GetShape()
        || values[j].GetTensorTypeAndShapeInfo().GetElementCount()!=n)
      throw std::runtime_error("Vocos shape mismatch");
    if(j==0) continue;
    const float* part=values[j].GetTensorData<float>();
    const float* ref_part=reference[j].GetTensorData<float>();
    for(size_t i=0;i<n;++i){
      const double a=static_cast<double>(magnitude[i])*part[i];
      const double b=static_cast<double>(ref_magnitude[i])*ref_part[i];
      delta+=(a-b)*(a-b);energy+=b*b;
    }
  }
  return std::sqrt(delta/std::max(energy,1e-30));
}

class AdaptiveStage::Impl {
 public:
  Impl(std::string name,std::string file,int count):stage(std::move(name)),model(std::move(file)),threads(count){}
  std::string stage,model,key,blocked,reason;
  int threads;
  std::unique_ptr<Ort::Session> session;
  bool qualified=false;
  int qnn_nodes=0,cpu_nodes=0;
  void Event(const std::string& backend,const std::string& why,double load=0,double elapsed=0,double error=0,double reference_ms=0,bool compared=false) {
    std::ostringstream s;s<<"{\"stage\":"<<Quote(stage)<<",\"backend\":"<<Quote(backend)<<",\"reason\":"<<Quote(why.substr(0,1500))<<",\"loadMs\":"<<load<<",\"runMs\":"<<elapsed<<",\"relativeError\":"<<error;
    s<<",\"referenceCompared\":"<<(compared?"true":"false")<<",\"referenceRunMs\":"<<reference_ms;
    if(stage=="decoder")s<<",\"qnnNodes\":"<<qnn_nodes<<",\"cpuNodes\":"<<cpu_nodes;
    s<<'}';request.events.push_back(s.str());
  }
  std::vector<Ort::Value> Run(Ort::Session& cpu,const char* const* names,Ort::Value* values,size_t count,const char* const* outputs,size_t output_count,int64_t frames) {
    if(!Active())return {};
    auto cpu_run=[&](){auto t=Clock::now();auto out=cpu.Run({},names,values,count,outputs,output_count);Event("cpu",reason,0,Millis(t));return out;};
    if(request.mode=="cpu" || ((request.mode=="auto" || request.mode=="gpu") && stage=="decoder")){
      reason=request.mode=="cpu"?"selected CPU":(request.mode=="gpu"?"GPU vocoder only; skip unsupported QNN GPU decoder":"automatic mode retains CPU decoder");
      return cpu_run();
    }
    const std::string mode=request.mode=="auto"?"gpu":request.mode;
    const std::string new_key=mode+":"+std::to_string(frames);
    if(blocked==mode){return cpu_run();}
    double load=0,elapsed=0,error=0,reference_ms=0;
    auto loading_started=Clock::now();bool creating=false,compared=false;
    try {
      if(request.fault=="init")throw std::runtime_error("injected initialization failure");
      if(key!=new_key || !session){
        session.reset();qualified=false;qnn_nodes=0;cpu_nodes=0;
        auto t=Clock::now();loading_started=t;creating=true;Ort::SessionOptions options;options.SetIntraOpNumThreads(threads);options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        // Static odd-length ZipVoice graphs expose a shape-folding bug in
        // ORT's generic optimizers. Keep the original graph for decoder EP
        // partitioning; never pad or truncate attention inputs to work around it.
        if(stage=="decoder")options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_DISABLE_ALL);
        if(stage=="vocoder") options.AddConfigEntry("session.disable_cpu_ep_fallback","1");
        else options.EnableProfiling((request.profiles+"/decoder-"+mode).c_str());
        if(stage=="vocoder") {Ort::ThrowOnError(Ort::GetApi().AddFreeDimensionOverrideByName(options,"batch_size",1));Ort::ThrowOnError(Ort::GetApi().AddFreeDimensionOverrideByName(options,"time",256));}
        else {Ort::ThrowOnError(Ort::GetApi().AddFreeDimensionOverrideByName(options,"N",1));Ort::ThrowOnError(Ort::GetApi().AddFreeDimensionOverrideByName(options,"T",frames));}
        {
          std::lock_guard<std::mutex> lock(registration_gate);
          if(!registered){
            std::string adsp=request.skels+";"+request.libs;const char* old=std::getenv("ADSP_LIBRARY_PATH");if(old&&*old)adsp+=";"+std::string(old);setenv("ADSP_LIBRARY_PATH",adsp.c_str(),1);
            qnn_env.RegisterExecutionProviderLibrary("kig-qnn",request.libs+"/libonnxruntime_providers_qnn.so");registered=true;
          }
        }
        std::vector<Ort::ConstEpDevice> devices;for(auto d:qnn_env.GetEpDevices())if(std::string(d.EpName())=="kig-qnn")devices.push_back(d);
        if(devices.empty())throw std::runtime_error("QNN exposes no compatible device");
        std::unordered_map<std::string,std::string> opts{{"backend_path",request.libs+(mode=="htp"?"/libQnnHtp.so":"/libQnnGpu.so")}};
        if(mode=="htp"){opts["htp_performance_mode"]="burst";opts["enable_htp_fp16_precision"]="1";}
        options.AppendExecutionProvider_V2(qnn_env,{devices.front()},opts);
        session=std::make_unique<Ort::Session>(qnn_env,model.c_str(),options);key=new_key;load=Millis(t);creating=false;
      }
      if(!Active())return {};
      if(request.fault=="run")throw std::runtime_error("injected inference failure");
      auto t=Clock::now();auto out=session->Run({},names,values,count,outputs,output_count);elapsed=Millis(t);
      if(!Active())return {};
      if(out.size()!=output_count)throw std::runtime_error("Unexpected QNN output count");
      // Validate every result before stitching, even on the GPU fast path.
      for(const auto& value:out){
        auto info=value.GetTensorTypeAndShapeInfo();
        if(info.GetElementType()!=ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)throw std::runtime_error("Unexpected QNN output type");
        if(stage=="vocoder" && info.GetShape()!=std::vector<int64_t>({1,513,256}))throw std::runtime_error("Unexpected QNN vocoder output shape");
        const float* data=value.GetTensorData<float>();
        if(!std::all_of(data,data+info.GetElementCount(),[](float x){return std::isfinite(x);}))throw std::runtime_error("Non-finite QNN output");
      }
      // A GPU session qualifies once. HTP and fault injection keep the CPU comparison.
      const bool need_qualification = !qualified || (mode == "htp") || (!request.fault.empty());
      if(need_qualification){
        if(stage=="decoder" && !qualified){
          Ort::AllocatorWithDefaultOptions allocator;auto path=session->EndProfilingAllocated(allocator);
          std::ifstream file(path.get());auto profile=nlohmann::json::parse(file);file.close();std::remove(path.get());
          for(const auto& event:profile)if(event.contains("args") && event["args"].contains("provider")){std::string provider=event["args"]["provider"];if(provider=="kig-qnn")++qnn_nodes;else if(provider=="CPUExecutionProvider")++cpu_nodes;else if(!provider.empty())throw std::runtime_error("Unexpected decoder execution provider: "+provider);}
          if(qnn_nodes==0)throw std::runtime_error("No decoder operators executed on QNN; retain CPU");
        }
        auto reference_started=Clock::now();
        auto ref=cpu.Run({},names,values,count,outputs,output_count);
        reference_ms=Millis(reference_started);
        if(!Active())return {};
        if(ref.size()!=out.size())throw std::runtime_error("Output count mismatch");
        double delta=0,energy=0;
        for(size_t j=0;j<out.size();++j){auto info=out[j].GetTensorTypeAndShapeInfo();if(info.GetShape()!=ref[j].GetTensorTypeAndShapeInfo().GetShape())throw std::runtime_error("Output shape mismatch");
          if(stage!="vocoder")for(size_t i=0;i<info.GetElementCount();++i){double a=out[j].GetTensorData<float>()[i],b=ref[j].GetTensorData<float>()[i];delta+=(a-b)*(a-b);energy+=b*b;}
        }
        const double relative_error=stage=="vocoder" ? VocosRelativeError(out,ref)
              : std::sqrt(delta/std::max(energy,1e-30));
        if(!std::isfinite(relative_error))throw std::runtime_error("Non-finite numerical qualification");
        error=relative_error;compared=true;
        if(error>0.02 || request.fault=="quality")throw std::runtime_error("Numerical qualification failed: relative error="+std::to_string(error)+" (limit 0.02)");
        qualified=true;
      }
      reason.clear();Event(mode+(cpu_nodes?"+cpu":""),stage=="vocoder"?"strict QNN; CPU encoder retained":"profile-verified QNN partitions; unsupported nodes on CPU",load,elapsed,error,reference_ms,compared);return out;
    }catch(const std::exception& e){
      if(creating)load=Millis(loading_started);
      session.reset();qualified=false;
      if(!Active())return {};
      blocked=mode;reason=e.what();Event("fallback",reason,load,elapsed,error,reference_ms,compared);
      return cpu_run();
    }
  }
};
AdaptiveStage::AdaptiveStage(std::string name,std::string file,int count):impl_(std::make_unique<Impl>(std::move(name),std::move(file),count)){}
AdaptiveStage::~AdaptiveStage()=default;
std::vector<Ort::Value> AdaptiveStage::Run(Ort::Session& cpu,const char* const* names,Ort::Value* values,size_t count,const char* const* outputs,size_t output_count,int64_t frames) {return impl_->Run(cpu,names,values,count,outputs,output_count,frames);}

std::vector<Ort::Value> Vocos(AdaptiveStage& stage,Ort::Session& cpu,const char* const* names,Ort::Value& mel,const char* const* outputs,size_t count) {
  if(!Active())return {};
  auto shape=mel.GetTensorTypeAndShapeInfo().GetShape();int64_t frames=shape.at(2);
  if(request.trace_seed){
    auto info=mel.GetTensorTypeAndShapeInfo();
    request.mel_hashes.push_back(HashFloatInput(mel.GetTensorData<float>(),info.GetElementCount()));
  }
  if(request.mode=="cpu" || frames<256){
    auto t=Clock::now();auto out=cpu.Run({},names,&mel,1,outputs,count);request.events.push_back("{\"stage\":\"vocoder\",\"backend\":\"cpu\",\"reason\":"+Quote(frames<256?"shorter than qualified 256-frame window":"selected CPU")+",\"runMs\":"+std::to_string(Millis(t))+"}");return out;
  }
  if(shape[0]!=1 || shape[1]!=100 || count!=3)throw std::runtime_error("Unexpected Vocos shape");
  Ort::AllocatorWithDefaultOptions allocator;auto memory=Ort::MemoryInfo::CreateCpu(OrtArenaAllocator,OrtMemTypeDefault);
  std::vector<Ort::Value> stitched;int64_t result_shape[]={1,513,frames};for(int i=0;i<3;++i)stitched.push_back(Ort::Value::CreateTensor<float>(allocator,result_shape,3));
  const float* source=mel.GetTensorData<float>();std::vector<float> tile(100*256);int64_t tile_shape[]={1,100,256};int64_t written=0;
  for(int64_t start=0;;){
    if(!Active())return {};
    for(int c=0;c<100;++c)std::copy_n(source+c*frames+start,256,tile.data()+c*256);
    auto value=Ort::Value::CreateTensor<float>(memory,tile.data(),tile.size(),tile_shape,3);
    auto out=stage.Run(cpu,names,&value,1,outputs,count,256);if(out.empty())return {};
    int64_t end=start+256>=frames?frames:start+224;
    for(size_t i=0;i<3;++i){if(out[i].GetTensorTypeAndShapeInfo().GetShape()!=std::vector<int64_t>({1,513,256}))throw std::runtime_error("Invalid Vocos tile output");for(int bin=0;bin<513;++bin)std::copy_n(out[i].GetTensorData<float>()+bin*256+(written-start),end-written,stitched[i].GetTensorMutableData<float>()+bin*frames+written);}
    written=end;
    if(written>=frames)break;
    start=std::min(start+192,frames-256);
  }
  // Diagnostic quality gate only: compare the stitched GPU output to the
  // original whole-segment CPU Vocos using the identical decoder mel.
  if(request.mode=="gpu" && request.fault=="compare-vocoder" && Active()){
    auto start=Clock::now();
    auto reference=cpu.Run({},names,&mel,1,outputs,count);
    auto reference_ms=Millis(start);
    if(!Active())return {};
    const double error=VocosRelativeError(stitched,reference);
    const bool passed=std::isfinite(error) && error<=0.02;
    request.events.push_back("{\"stage\":\"vocoder-quality\",\"backend\":\"cpu-reference\","
        "\"frames\":"+std::to_string(frames)+",\"relativeError\":"+
        (std::isfinite(error)?std::to_string(error):"null")+
        ",\"passed\":"+(passed?"true":"false")+",\"referenceRunMs\":"+std::to_string(reference_ms)+"}");
    if(!passed)return reference;
  }
  return stitched;
}
} // namespace kig

extern "C" JNIEXPORT jstring JNICALL Java_com_kigtts_clone_zipvoice_QualcommNative_libraryDirectory(JNIEnv* env,jobject){Dl_info info{};if(!dladdr(reinterpret_cast<void*>(&OrtGetApiBase),&info)||!info.dli_fname)return env->NewStringUTF("");std::string p(info.dli_fname);return env->NewStringUTF(p.substr(0,p.find_last_of('/')).c_str());}
extern "C" JNIEXPORT void JNICALL Java_com_kigtts_clone_zipvoice_QualcommNative_begin(JNIEnv* env,jobject,jstring mode,jstring libs,jstring skels,jobject guard,jstring fault,jstring profiles){
  if(kig::request.guard)env->DeleteGlobalRef(kig::request.guard);kig::request={};kig::request.mode=kig::Utf8(env,mode);kig::request.libs=kig::Utf8(env,libs);kig::request.skels=kig::Utf8(env,skels);kig::request.fault=kig::Utf8(env,fault);kig::request.profiles=kig::Utf8(env,profiles);kig::request.jni=env;kig::request.guard=env->NewGlobalRef(guard);auto cls=env->GetObjectClass(guard);kig::request.check=env->GetMethodID(cls,"isActive","()Z");env->DeleteLocalRef(cls);
}
extern "C" JNIEXPORT void JNICALL Java_com_kigtts_clone_zipvoice_QualcommNative_setProbeSeed(
    JNIEnv*,jobject,jint seed,jboolean trace){
  if(seed<0)return;
  kig::request.seed=seed;
  kig::request.trace_seed=trace;
  kig::request.noise=std::make_unique<sherpa_onnx::NormalDataGenerator>(0.0f,1.0f,seed);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_kigtts_clone_zipvoice_QualcommNative_end(JNIEnv* env,jobject){
  auto& req=kig::request;
  std::string result="{\"requestedMode\":"+kig::Quote(req.mode)+",\"encoder\":\"cpu\",\"events\":[";
  for(size_t i=0;i<req.events.size();++i){if(i)result+=',';result+=req.events[i];}
  result+=']';
  if(req.seed>=0){
    result+=",\"diffusionSeed\":"+std::to_string(req.seed);
    if(req.trace_seed){
      result+=",\"noiseFingerprints\":"+nlohmann::json(req.noise_hashes).dump();
      result+=",\"melFingerprints\":"+nlohmann::json(req.mel_hashes).dump();
    }
  }
  result+='}';
  if(req.guard)env->DeleteGlobalRef(req.guard);
  req={};
  return env->NewStringUTF(result.c_str());
}
