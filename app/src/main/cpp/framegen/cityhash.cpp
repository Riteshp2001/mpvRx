// SPDX-FileCopyrightText: 2011 Google, Inc.
// SPDX-License-Identifier: MIT

#include <algorithm>
#include <cstring>
#include <utility>

#include "cityhash.h"

namespace Common {

static inline u64 swap64(u64 val) {
    return __builtin_bswap64(val);
}

static inline u32 swap32(u32 val) {
    return __builtin_bswap32(val);
}

static inline u64 unaligned_load64(const char* p) {
    u64 result;
    std::memcpy(&result, p, sizeof(result));
    return result;
}

static inline u32 unaligned_load32(const char* p) {
    u32 result;
    std::memcpy(&result, p, sizeof(result));
    return result;
}

static inline u64 Fetch64(const char* p) {
    return unaligned_load64(p);
}

static inline u32 Fetch32(const char* p) {
    return unaligned_load32(p);
}

static constexpr u64 k0 = 0xc3a5c85c97cb3127ULL;
static constexpr u64 k1 = 0xb492b66fbe98f273ULL;
static constexpr u64 k2 = 0x9ae16a3b2f90404fULL;

static inline u64 Rotate(u64 val, int shift) {
    return shift == 0 ? val : ((val >> shift) | (val << (64 - shift)));
}

static inline u64 ShiftMix(u64 val) {
    return val ^ (val >> 47);
}

static inline u64 HashLen16(u64 u, u64 v) {
    return Hash128to64(u128{u, v});
}

static inline u64 HashLen16(u64 u, u64 v, u64 mul) {
    u64 a = (u ^ v) * mul;
    a ^= (a >> 47);
    u64 b = (v ^ a) * mul;
    b ^= (b >> 47);
    b *= mul;
    return b;
}

static u64 HashLen0to16(const char* s, size_t len) {
    if (len >= 8) {
        u64 mul = k2 + len * 2;
        u64 a = Fetch64(s) + k2;
        u64 b = Fetch64(s + len - 8);
        u64 c = Rotate(b, 37) * mul + a;
        u64 d = (Rotate(a, 25) + b) * mul;
        return HashLen16(c, d, mul);
    }
    if (len >= 4) {
        u64 mul = k2 + len * 2;
        u64 a = Fetch32(s);
        return HashLen16(len + (a << 3), Fetch32(s + len - 4), mul);
    }
    if (len > 0) {
        u8 a = static_cast<u8>(s[0]);
        u8 b = static_cast<u8>(s[len >> 1]);
        u8 c = static_cast<u8>(s[len - 1]);
        u32 y = static_cast<u32>(a) + (static_cast<u32>(b) << 8);
        u32 z = static_cast<u32>(len) + (static_cast<u32>(c) << 2);
        return ShiftMix(y * k2 ^ z * k0) * k2;
    }
    return k2;
}

static u64 HashLen17to32(const char* s, size_t len) {
    u64 mul = k2 + len * 2;
    u64 a = Fetch64(s) * k1;
    u64 b = Fetch64(s + 8);
    u64 c = Fetch64(s + len - 8) * mul;
    u64 d = Fetch64(s + len - 16) * k2;
    return HashLen16(Rotate(a + b, 43) + Rotate(c, 30) + d, a + Rotate(b + k2, 18) + c, mul);
}

static std::pair<u64, u64> WeakHashLen32WithSeeds(u64 w, u64 x, u64 y, u64 z, u64 a, u64 b) {
    a += w;
    b = Rotate(b + a + z, 21);
    u64 c = a;
    a += x;
    a += y;
    b += Rotate(a, 44);
    return std::make_pair(a + z, b + c);
}

static std::pair<u64, u64> WeakHashLen32WithSeeds(const char* s, u64 a, u64 b) {
    return WeakHashLen32WithSeeds(Fetch64(s), Fetch64(s + 8), Fetch64(s + 16), Fetch64(s + 24), a, b);
}

static u64 HashLen33to64(const char* s, size_t len) {
    u64 mul = k2 + len * 2;
    u64 a = Fetch64(s) * k2;
    u64 b = Fetch64(s + 8);
    u64 c = Fetch64(s + len - 24);
    u64 d = Fetch64(s + len - 32);
    u64 e = Fetch64(s + 16) * k2;
    u64 f = Fetch64(s + 24) * 9;
    u64 g = Fetch64(s + len - 8);
    u64 h = Fetch64(s + len - 16) * mul;
    u64 u = Rotate(a + g, 43) + (Rotate(b, 30) + c) * 9;
    u64 v = ((a + g) ^ d) + f + 1;
    u64 w = swap64((u + v) * mul) + h;
    u64 x = Rotate(e + f, 42) + c;
    u64 y = (swap64((v + w) * mul) + g) * mul;
    u64 z = e + f + c;
    a = swap64((x + z) * mul + y) + b;
    b = ShiftMix((z + a) * mul + d + h) * mul;
    return b + x;
}

u64 CityHash64(const char* s, size_t len) {
    if (len <= 32) {
        if (len <= 16) {
            return HashLen0to16(s, len);
        } else {
            return HashLen17to32(s, len);
        }
    } else if (len <= 64) {
        return HashLen33to64(s, len);
    }

    u64 x = Fetch64(s + len - 40);
    u64 y = Fetch64(s + len - 16) + Fetch64(s + len - 56);
    u64 z = HashLen16(Fetch64(s + len - 48) + len, Fetch64(s + len - 24));
    std::pair<u64, u64> v = WeakHashLen32WithSeeds(s + len - 64, len, z);
    std::pair<u64, u64> w = WeakHashLen32WithSeeds(s + len - 32, y + k1, x);
    x = x * k1 + Fetch64(s);

    len = (len - 1) & ~static_cast<size_t>(63);
    do {
        x = Rotate(x + y + v.first + Fetch64(s + 8), 37) * k1;
        y = Rotate(y + v.second + Fetch64(s + 48), 42) * k1;
        x ^= w.second;
        y += v.first + Fetch64(s + 40);
        z = Rotate(z + w.first, 33) * k1;
        v = WeakHashLen32WithSeeds(s, v.second * k1, x + w.first);
        w = WeakHashLen32WithSeeds(s + 32, z + w.second, y + Fetch64(s + 16));
        std::swap(z, x);
        s += 64;
        len -= 64;
    } while (len != 0);
    return HashLen16(HashLen16(v.first, w.first) + ShiftMix(y) * k1 + z,
                     HashLen16(v.second, w.second) + x);
}

u64 CityHash64WithSeed(const char* s, size_t len, u64 seed) {
    return CityHash64WithSeeds(s, len, k2, seed);
}

u64 CityHash64WithSeeds(const char* s, size_t len, u64 seed0, u64 seed1) {
    return HashLen16(CityHash64(s, len) - seed0, seed1);
}

} // namespace Common
