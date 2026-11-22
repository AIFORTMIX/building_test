#pragma once
#include "qingwei_api.h"
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstring>
#include <vector>

namespace qingwei {
class MemorySearch {
  public:
    static constexpr unsigned PagesPerBatch = 16;
    static constexpr unsigned PageBytes = 4096;
    static constexpr unsigned MaxResults = 10000;
    uint64_t begin = 0, end = 0, next = 0;
    bool active = false, limited = false;
    std::vector<uint64_t> results;
    bool Start(uint64_t first, uint64_t last, uint32_t value, bool floating) {
        begin = next = first;
        end = last;
        bits_ = value;
        floating_ = floating;
        active = first < last;
        limited = false;
        results.clear();
        return active;
    }
    template <class ReadBatch> void Step(ReadBatch read) {
        const auto started = std::chrono::steady_clock::now();
        // A failed page advances the cursor too. All-invalid ranges cannot trap
        // a rendering frame in an unbounded loop or wrap at UINT64_MAX.
        for (unsigned batch = 0; active && batch < 4; ++batch) {
            unsigned count = 0, bytes = 0;
            uint64_t cursor = next;
            while (cursor < end && count < PagesPerBatch) {
                const unsigned size = static_cast<unsigned>(std::min<uint64_t>(PageBytes, end - cursor));
                auto &item = items_[count++];
                item = {};
                item.addr = cursor;
                item.size = size;
                item.out_offset = bytes;
                item.status = -ECANCELED;
                bytes += size;
                cursor += size;
            }
            read(items_.data(), count, data_.data(), bytes);
            for (unsigned i = 0; i < count && !limited; ++i) {
                const auto &item = items_[i];
                if (item.status)
                    continue;
                for (unsigned offset = 0; offset + 4 <= item.size; offset += 4) {
                    uint32_t value;
                    std::memcpy(&value, data_.data() + item.out_offset + offset, 4);
                    bool matches = value == bits_;
                    if (floating_) {
                        float actual, wanted;
                        std::memcpy(&actual, &value, 4);
                        std::memcpy(&wanted, &bits_, 4);
                        matches = std::isfinite(actual) && std::fabs(actual - wanted) < 0.0001f;
                    }
                    if (matches) {
                        results.push_back(item.addr + offset);
                        if (results.size() == MaxResults) {
                            limited = true;
                            break;
                        }
                    }
                }
            }
            next = cursor;
            active = next < end && !limited;
            if (std::chrono::steady_clock::now() - started >= std::chrono::milliseconds(3))
                break;
        }
    }

  private:
    uint32_t bits_ = 0;
    bool floating_ = false;
    std::array<qingwei_batch_item_t, PagesPerBatch> items_{};
    std::array<unsigned char, PagesPerBatch * PageBytes> data_{};
};
} // namespace qingwei
