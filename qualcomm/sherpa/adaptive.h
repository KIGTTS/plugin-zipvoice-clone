// SPDX-License-Identifier: MIT
#pragma once
#include <onnxruntime_cxx_api.h>
#include <memory>
#include <string>
#include <vector>
namespace kig {
bool Active();
// Returns false for normal requests, preserving the unseeded path.
bool FillProbeNoise(float* data, size_t count);
class AdaptiveStage {
 public:
  AdaptiveStage(std::string stage, std::string model, int threads);
  ~AdaptiveStage();
  std::vector<Ort::Value> Run(Ort::Session& cpu, const char* const* inputs,
      Ort::Value* values, size_t input_count, const char* const* outputs, size_t output_count,
      int64_t frames = 0);
 private:
  class Impl;
  std::unique_ptr<Impl> impl_;
};
std::vector<Ort::Value> Vocos(AdaptiveStage& stage, Ort::Session& cpu,
    const char* const* inputs, Ort::Value& mel, const char* const* outputs, size_t output_count);
}
