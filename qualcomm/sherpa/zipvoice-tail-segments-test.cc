#include "zipvoice-tail-segments.h"

#include <cassert>
#include <iostream>

int main() {
  using Text = std::vector<std::string>;
  auto words = [](const std::string& s) { return static_cast<int>(s.size()); };
  using kig::MergeCommaTail;
  assert(MergeCommaTail(Text{}, 32, words).empty());
  assert((MergeCommaTail(Text{"short,", "unchanged."}, 32, words)
          == Text{"short,", "unchanged."}));
  assert((MergeCommaTail(Text{"first,", "left,", "right.", "one,", "two,", "three."}, 32, words)
          == Text{"first,", "left,right.", "one,two,three."}));
  assert((MergeCommaTail(Text{"first,", "left,", "right.", "tail,", "yep."}, 10, words)
          == Text{"first,", "left,", "right.", "tail,yep."}));
  assert((MergeCommaTail(Text{"first;", "x?", "y!", "z."}, 32, words)
          == Text{"first;", "x?", "y!", "z."}));
  assert((MergeCommaTail(Text{"first.", "end,"}, 32, words)
          == Text{"first.", "end,"}));
  std::cout << "six segment-policy regressions passed\n";
}
