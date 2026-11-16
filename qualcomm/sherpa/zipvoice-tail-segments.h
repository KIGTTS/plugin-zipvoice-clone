#ifndef KIG_ZIPVOICE_TAIL_SEGMENTS_H_
#define KIG_ZIPVOICE_TAIL_SEGMENTS_H_

#include <string>
#include <utility>
#include <vector>

namespace kig {

// Takes the stock, already normalized segments. The first segment is never
// extended. Subsequent segments can join only across a comma and within a
// bounded size; sentence boundaries and all punctuation stay in the input.
template <typename CountWords>
std::vector<std::string> MergeCommaTail(
    const std::vector<std::string>& segments, int limit, CountWords count_words) {
  std::vector<std::string> result;
  result.reserve(segments.size());
  for (const auto& segment : segments) {
    if (result.size() > 1 && !result.back().empty() && !segment.empty() &&
        result.back().back() == ',') {
      std::string combined = result.back() + segment;
      if (count_words(combined) <= limit) {
        result.back() = std::move(combined);
        continue;
      }
    }
    result.push_back(segment);
  }
  return result;
}

}  // namespace kig
#endif  // KIG_ZIPVOICE_TAIL_SEGMENTS_H_
