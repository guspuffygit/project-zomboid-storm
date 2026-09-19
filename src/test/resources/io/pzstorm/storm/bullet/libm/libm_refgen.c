/*
 * Reference generator for io.pzstorm.storm.bullet.libm.GlibcMath / GlibcRand.
 *
 * Calls the real glibc libm/libc (bound to the symbol versions libPZBulletNoOpenGL64.so imports)
 * on a deterministic, seeded input set and prints raw result bits. GlibcMathTest regenerates the
 * same inputs in Java (the input generator below uses only integer operations and exactly
 * rounded IEEE multiplies/divides/converts, which Java reproduces) and compares bit patterns,
 * NaN sign and payload included.
 *
 * Build/run (x86-64 Linux, CPU with FMA+AVX2 so libm picks the *_fma ifunc variants):
 *   gcc -O1 -fno-builtin -ffp-contract=off -o libm_refgen libm_refgen.c -lm
 *   ./libm_refgen libm_hardcases.txt | gzip -9 > libm-ref-<glibc version>.txt.gz
 *   ./libm_refgen -n <log2 count> ...        # other input count per function (default 24)
 *   ./libm_refgen dump <func> > <func>.bin   # every output, raw little-endian (dev use)
 *
 * Output lines:
 *   V <glibc version> fma=<0|1> avx2=<0|1>
 *   N <func> <count> <chunk>            input count and digest chunk size
 *   D <func> <chunk#> <inhash> <outhash>  digest of one chunk of inputs / outputs
 *   R <func> <in...> <out...>           raw record (special values, hard cases), hex bits
 *   G <seed|default> <count> <hash> <first 8 values>  rand() sequences
 */
#include <gnu/libc-version.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Bullet's imports: fmod@GLIBC_2.2.5 (compat wrapper, not the 2.38 fmod), pow@GLIBC_2.29,
   powf@GLIBC_2.27; everything else only has GLIBC_2.2.5. */
extern double fmod_ref(double, double);
extern double pow_ref(double, double);
extern float powf_ref(float, float);
__asm__(".symver fmod_ref,fmod@GLIBC_2.2.5");
__asm__(".symver pow_ref,pow@GLIBC_2.29");
__asm__(".symver powf_ref,powf@GLIBC_2.27");
extern double sin(double);
extern double cos(double);
extern void sincos(double, double *, double *);
extern double asin(double);
extern double acos(double);
extern double atan2(double, double);
extern double sqrt(double);
extern float sqrtf(float);

/* inputs per function; the committed reference files use 1 << 24 */
static unsigned N_PER_FUNC = 1u << 24;
#define CHUNK (1u << 16)

static uint64_t mix(uint64_t z) {
  z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
  z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
  return z ^ (z >> 31);
}

/* splitmix64 stream, seeded per (function, index). */
static uint64_t st;
static void seed(int f, uint64_t i) { st = mix(((uint64_t)f << 40) ^ i ^ 0x5eed5eed00000000ULL); }
static uint64_t nx(void) {
  st += 0x9e3779b97f4a7c15ULL;
  return mix(st);
}

static double d_(uint64_t b) { double d; memcpy(&d, &b, 8); return d; }
static uint64_t b_(double d) { uint64_t b; memcpy(&b, &d, 8); return b; }
static float f_(uint32_t b) { float f; memcpy(&f, &b, 4); return f; }
static uint32_t fb_(float f) { uint32_t b; memcpy(&b, &f, 4); return b; }

/* ---- input building blocks ---- */

static const uint64_t SPECIAL_D[] = {
    0x0000000000000000ULL, 0x0000000000000001ULL, 0x000fffffffffffffULL, 0x0010000000000000ULL,
    0x3fe0000000000000ULL, 0x3ff0000000000000ULL, 0x3fefffffffffffffULL, 0x3ff0000000000001ULL,
    0x3ff8000000000000ULL, 0x4000000000000000ULL, 0x4008000000000000ULL, 0x3fd0000000000000ULL,
    0x4024000000000000ULL, 0x400921fb54442d18ULL, 0x3ff921fb54442d18ULL, 0x3fe921fb54442d18ULL,
    0x7fefffffffffffffULL, 0x7ff0000000000000ULL, 0x7ff8000000000000ULL, 0x7ff0000000000001ULL,
    0x7ff8dead0000beefULL, 0x7ff4000000001234ULL, 0x7fffffffffffffffULL, 0x7e37e43c8800759cULL,
    0x01a56e1fc2f8f359ULL, 0x3fb999999999999aULL, 0x7fe0000000000000ULL, 0x4330000000000000ULL,
    0x433fffffffffffffULL, 0x43f0000000000000ULL, 0x3fe8000000000000ULL, 0x44b52d02c7e14af6ULL,
    0x7506ac5b262ca1ffULL, 0x4014000000000000ULL, 0x3e40000000000000ULL, 0x3e50000000000000ULL,
    0x3feb600000000000ULL, 0x400368fd00000000ULL, 0x419921fb00000000ULL, 0x3fc020c49ba5e354ULL,
    0x3c88000000000000ULL, 0x3fc0000000000000ULL, 0x3fef000000000000ULL, 0x4340000000000000ULL,
    0x4340000000000001ULL, 0x3cb0000000000000ULL, 0x0000000000000003ULL, 0x0008000000000000ULL,
};
#define NSD ((int)(sizeof SPECIAL_D / sizeof SPECIAL_D[0]) * 2)
static uint64_t special_d(uint64_t k) {
  k %= NSD;
  return SPECIAL_D[k >> 1] | ((k & 1) << 63);
}

static const uint32_t SPECIAL_F[] = {
    0x00000000, 0x00000001, 0x007fffff, 0x00800000, 0x3f000000, 0x3f800000, 0x3f7fffff,
    0x3f800001, 0x40000000, 0x40400000, 0x3e800000, 0x41200000, 0x7f7fffff, 0x7f800000,
    0x7fc00000, 0x7f800001, 0x7fc0dead, 0x7fa00123, 0x7fffffff, 0x4b800000, 0x4b7fffff,
    0x4b000001, 0x3dcccccd, 0x43000000, 0x3fc00000, 0x7149f2ca, 0x0da24260, 0x00000003,
    0x00400000, 0x4f000000, 0x3f3504f3, 0x40490fdb,
};
#define NSF ((int)(sizeof SPECIAL_F / sizeof SPECIAL_F[0]) * 2)
static uint32_t special_f(uint64_t k) {
  k %= NSF;
  return SPECIAL_F[k >> 1] | ((uint32_t)(k & 1) << 31);
}

/* random sign | biased exponent in [lo, hi] | random mantissa */
static uint64_t d_exp(int lo, int hi) {
  uint64_t e = lo + nx() % (uint64_t)(hi - lo + 1);
  uint64_t m = nx() & 0x000fffffffffffffULL;
  uint64_t s = nx() & 1;
  return (s << 63) | (e << 52) | m;
}
static uint64_t d_exp_pos(int lo, int hi) { return d_exp(lo, hi) & 0x7fffffffffffffffULL; }

/* signed delta with a random magnitude of up to 2^(maxbits-1) */
static int64_t delta(int maxbits) {
  int k = (int)(nx() % (uint64_t)maxbits);
  int64_t d = (int64_t)(nx() & ((1ULL << k) - 1));
  return (nx() & 1) ? -d : d;
}

static uint64_t near_bits(uint64_t base, int maxbits) { return base + (uint64_t)delta(maxbits); }

static uint64_t rand_sign(uint64_t b) { return b ^ ((nx() & 1) << 63); }

static uint32_t f_exp(int lo, int hi) {
  uint32_t e = lo + (uint32_t)(nx() % (uint64_t)(hi - lo + 1));
  uint32_t m = (uint32_t)nx() & 0x7fffff;
  uint32_t s = (uint32_t)nx() & 1;
  return (s << 31) | (e << 23) | m;
}

/* ---- per-function input generators; cat = i % ncat ---- */

enum { F_SIN, F_COS, F_SINCOS, F_ASIN, F_ACOS, F_ATAN2, F_POW, F_FMOD, F_SQRT, F_POWF, F_SQRTF, NF };
static const char *NAMES[NF] = {"sin", "cos", "sincos", "asin", "acos", "atan2",
                                "pow", "fmod", "sqrt", "powf", "sqrtf"};

static const uint64_t TRIG_EDGES[] = {0x3e40000000000000ULL, 0x3e50000000000000ULL,
                                      0x3feb600000000000ULL, 0x400368fd00000000ULL,
                                      0x419921fb00000000ULL, 0x3fc020c49ba5e354ULL,
                                      0x7fefffffffffffffULL, 0x3ff921fb54442d18ULL};
static const double PIO2 = 0x1.921fb54442d18p0;

static uint64_t gen_trig(uint64_t i) {
  switch (i % 8) {
    case 0: return nx();
    case 1: return d_exp(0x3c0, 0x41f);
    case 2: return d_exp(0x400, 0x7fe);
    case 3: {
      int sh = 11 + (int)(nx() % 53);
      double k = (double)(int64_t)(nx() >> sh);
      return rand_sign(near_bits(b_(k * PIO2), 1 + (int)(nx() % 12)));
    }
    case 4: return rand_sign(near_bits(TRIG_EDGES[nx() % 8], 41));
    case 5: return d_exp(0x3f0, 0x402);
    case 6: return special_d(i / 8);
    default: return d_exp(0x000, 0x3e5);
  }
}

static const uint32_t ASIN_EDGES[] = {0x3c880000, 0x3e500000, 0x3fc00000, 0x3fd00000, 0x3fe00000,
                                      0x3fe80000, 0x3fed8000, 0x3fee8000, 0x3fef0000, 0x3ff00000};

static uint64_t gen_asin(uint64_t i) {
  switch (i % 8) {
    case 0: return nx();
    case 1: return d_exp(0x3c0, 0x3fe);
    case 2: return d_exp(0x000, 0x3c9);
    case 3: {
      int sh = (int)(nx() % 53);
      uint64_t d = nx() % (1ULL << sh);
      return rand_sign((nx() & 3) ? 0x3ff0000000000000ULL - d : 0x3ff0000000000000ULL + d);
    }
    case 4: return rand_sign(near_bits((uint64_t)ASIN_EDGES[nx() % 10] << 32, 41));
    case 5: return d_exp(0x3fc, 0x3fe);
    case 6: return special_d(i / 8);
    default: return d_exp(0x3ff, 0x7ff);
  }
}

static uint64_t gen_sqrt(uint64_t i) {
  switch (i % 4) {
    case 0: return nx();
    case 1: return d_exp(0x3c0, 0x440);
    case 2: return special_d(i / 4);
    default: return d_exp(0x000, 0x001);
  }
}

static void gen_atan2(uint64_t i, uint64_t *y, uint64_t *x) {
  switch (i % 10) {
    case 0: *y = nx(); *x = nx(); return;
    case 1: *y = d_exp(0x3e0, 0x41f); *x = d_exp(0x3e0, 0x41f); return;
    case 2: {
      int e = 1 + (int)(nx() % 0x7fe);
      int ey = e + (int)(nx() % 129) - 64;
      if (ey < 0) ey = 0;
      if (ey > 0x7fe) ey = 0x7fe;
      *x = d_exp(e, e);
      *y = d_exp(ey, ey);
      return;
    }
    case 3: {
      double xv = d_(d_exp(0x3f0, 0x410));
      double t = d_(d_exp_pos(0x3f6, 0x3ff));
      uint64_t yv = rand_sign(b_(xv * t));
      if (nx() & 1) { *y = yv; *x = b_(xv); } else { *y = b_(xv); *x = yv; }
      return;
    }
    case 4:
      *y = (nx() & 1) ? d_exp(0x000, 0x040) : d_exp(0x7b0, 0x7fe);
      *x = (nx() & 1) ? d_exp(0x000, 0x040) : d_exp(0x7b0, 0x7fe);
      return;
    case 5: *y = special_d(i / 10); *x = special_d(i / 10 / NSD); return;
    case 6:
      if (nx() & 1) { *y = special_d(nx()); *x = nx(); } else { *y = nx(); *x = special_d(nx()); }
      return;
    case 7: {
      uint64_t b = d_exp(0x3c0, 0x440);
      *x = b;
      *y = rand_sign(near_bits(b & 0x7fffffffffffffffULL, 30));
      return;
    }
    case 8: *y = d_exp(0x000, 0x003); *x = d_exp(0x000, 0x003); return;
    default: *y = d_exp(0x3f0, 0x40f); *x = d_exp(0x3f0, 0x40f); return;
  }
}

static const double POW_TARGETS[] = {1023.0, 1024.0, -1022.0, -1074.0, -1075.0, 1025.0, -1021.0, 0.5};

static void gen_pow(uint64_t i, uint64_t *x, uint64_t *y) {
  switch (i % 12) {
    case 0: *x = nx(); *y = nx(); return;
    case 1: *x = d_exp_pos(0x3f0, 0x40f); *y = d_exp(0x3f0, 0x40f); return;
    case 2: {
      int sh = (int)(nx() % 53);
      uint64_t d = nx() % (1ULL << sh);
      *x = (nx() & 1) ? 0x3ff0000000000000ULL - d : 0x3ff0000000000000ULL + d;
      *y = d_exp(0x3f0, 0x43f);
      return;
    }
    case 3: {
      int sh = 4 + (int)(nx() % 60);
      *y = rand_sign(b_((double)(int64_t)(nx() >> sh)));
      *x = d_exp(0x380, 0x47f);
      return;
    }
    case 4: *x = nx() | 0x8000000000000000ULL; *y = b_((double)((int)(nx() % 81) - 40)); return;
    case 5: {
      int e = 0x3f0 + (int)(nx() % 32);
      if (e == 0x3ff) e = 0x400;
      *x = d_exp_pos(e, e);
      double t = POW_TARGETS[nx() % 8] / (double)(e - 0x3ff);
      *y = near_bits(b_(t), 1 + (int)(nx() % 30));
      if (nx() & 1) *x |= 0x8000000000000000ULL;
      return;
    }
    case 6: *x = special_d(i / 12); *y = special_d(i / 12 / NSD); return;
    case 7:
      if (nx() & 1) { *x = special_d(nx()); *y = nx(); } else { *x = nx(); *y = special_d(nx()); }
      return;
    case 8: *x = d_exp(0x000, 0x000); *y = d_exp(0x3f0, 0x40a); return;
    case 9:
      *x = d_exp_pos(0x3c0, 0x440);
      *y = (nx() & 1) ? d_exp(0x380, 0x3cf) : d_exp(0x43e, 0x7fe);
      return;
    case 10: *x = d_exp_pos(0x000, 0x7fe); *y = d_exp(0x3e0, 0x40f); return;
    default:
      *x = b_((double)(nx() % 1000));
      *y = b_((double)((int)(nx() % 1401) - 700) * 0.5);
      return;
  }
}

static void gen_fmod(uint64_t i, uint64_t *x, uint64_t *y) {
  switch (i % 10) {
    case 0: *x = nx(); *y = nx(); return;
    case 1: *x = d_exp(0x3c0, 0x440); *y = d_exp(0x3c0, 0x440); return;
    case 2: {
      int ey = 53 + (int)(nx() % (2035 - 53 + 1));
      int ex = ey + (int)(nx() % 13);
      if (ex > 0x7fe) ex = 0x7fe;
      *x = d_exp(ex, ex);
      *y = d_exp(ey, ey);
      return;
    }
    case 3: {
      int ey = (int)(nx() % 0x7ff);
      int ex = ey + (int)(nx() % 2047);
      if (ex > 0x7fe) ex = 0x7fe;
      *x = d_exp(ex, ex);
      *y = d_exp(ey, ey);
      return;
    }
    case 4: *x = d_exp(0x000, 0x7fe); *y = d_exp(0x000, 0x000); return;
    case 5: *x = d_exp(0x000, 0x000); *y = d_exp(0x000, 0x000); return;
    case 6: *x = special_d(i / 10); *y = special_d(i / 10 / NSD); return;
    case 7:
      if (nx() & 1) { *x = special_d(nx()); *y = nx(); } else { *x = nx(); *y = special_d(nx()); }
      return;
    case 8: {
      double yv = d_(d_exp(0x300, 0x500));
      int sh = 4 + (int)(nx() % 60);
      double k = (double)(int64_t)(nx() >> sh);
      *y = b_(yv);
      *x = rand_sign(near_bits(b_(yv * k) & 0x7fffffffffffffffULL, 1 + (int)(nx() % 8)));
      return;
    }
    default: *y = d_exp(0x001, 0x7fe) & 0xfff0000000000000ULL; *x = d_exp(0x000, 0x7fe); return;
  }
}

static const float POWF_TARGETS[] = {128.0f, 127.0f, -126.0f, -149.0f, -150.0f, 129.0f, -125.0f, 0.5f};

static void gen_powf(uint64_t i, uint32_t *x, uint32_t *y) {
  switch (i % 10) {
    case 0: { uint64_t r = nx(); *x = (uint32_t)r; *y = (uint32_t)(r >> 32); return; }
    case 1: *x = f_exp(0x70, 0x8f) & 0x7fffffff; *y = f_exp(0x70, 0x8f); return;
    case 2: {
      int sh = (int)(nx() % 24);
      uint32_t d = (uint32_t)(nx() % (1ULL << sh));
      *x = (nx() & 1) ? 0x3f800000u - d : 0x3f800000u + d;
      *y = f_exp(0x70, 0x9f);
      return;
    }
    case 3: {
      int sh = 40 + (int)(nx() % 24);
      uint32_t yv = fb_((float)(int64_t)(nx() >> sh));
      *y = yv ^ ((uint32_t)(nx() & 1) << 31);
      *x = f_exp(0x40, 0xbf);
      return;
    }
    case 4: *x = (uint32_t)nx() | 0x80000000u; *y = fb_((float)((int)(nx() % 81) - 40)); return;
    case 5: {
      int e = 0x70 + (int)(nx() % 32);
      if (e == 0x7f) e = 0x80;
      *x = f_exp(e, e) & 0x7fffffff;
      float t = POWF_TARGETS[nx() % 8] / (float)(e - 0x7f);
      *y = fb_(t) + (uint32_t)(int32_t)delta(1 + (int)(nx() % 16));
      if (nx() & 1) *x |= 0x80000000u;
      return;
    }
    case 6: *x = special_f(i / 10); *y = special_f(i / 10 / NSF); return;
    case 7:
      if (nx() & 1) { *x = special_f(nx()); *y = (uint32_t)nx(); }
      else { *x = (uint32_t)nx(); *y = special_f(nx()); }
      return;
    case 8: *x = f_exp(0x00, 0x00); *y = f_exp(0x70, 0x84); return;
    default: *x = f_exp(0x00, 0xfe) & 0x7fffffff; *y = f_exp(0x60, 0x8f); return;
  }
}

static uint32_t gen_sqrtf(uint64_t i) {
  switch (i % 3) {
    case 0: return (uint32_t)nx();
    case 1: return special_f(i / 3);
    default: return f_exp(0x00, 0x00);
  }
}

/* ---- evaluation ---- */

/* in[0..1], out[0..1] as raw bits (floats zero-extended) */
static int eval(int f, uint64_t i, uint64_t in[2], uint64_t out[2]) {
  seed(f, i);
  in[1] = out[1] = 0;
  switch (f) {
    case F_SIN: in[0] = gen_trig(i); out[0] = b_(sin(d_(in[0]))); return 1;
    case F_COS: in[0] = gen_trig(i); out[0] = b_(cos(d_(in[0]))); return 1;
    case F_SINCOS: {
      double s, c;
      in[0] = gen_trig(i);
      sincos(d_(in[0]), &s, &c);
      out[0] = b_(s);
      out[1] = b_(c);
      return 1;
    }
    case F_ASIN: in[0] = gen_asin(i); out[0] = b_(asin(d_(in[0]))); return 1;
    case F_ACOS: in[0] = gen_asin(i); out[0] = b_(acos(d_(in[0]))); return 1;
    case F_ATAN2:
      gen_atan2(i, &in[0], &in[1]);
      out[0] = b_(atan2(d_(in[0]), d_(in[1])));
      return 2;
    case F_POW: gen_pow(i, &in[0], &in[1]); out[0] = b_(pow_ref(d_(in[0]), d_(in[1]))); return 2;
    case F_FMOD:
      gen_fmod(i, &in[0], &in[1]);
      out[0] = b_(fmod_ref(d_(in[0]), d_(in[1])));
      return 2;
    case F_SQRT: in[0] = gen_sqrt(i); out[0] = b_(sqrt(d_(in[0]))); return 1;
    case F_POWF: {
      uint32_t x, y;
      gen_powf(i, &x, &y);
      in[0] = x;
      in[1] = y;
      out[0] = fb_(powf_ref(f_(x), f_(y)));
      return 2;
    }
    default: in[0] = gen_sqrtf(i); out[0] = fb_(sqrtf(f_((uint32_t)in[0]))); return 1;
  }
}

static uint64_t hash(uint64_t h, uint64_t v) {
  h = (h ^ v) * 0x9e3779b97f4a7c15ULL;
  return h ^ (h >> 29);
}

static int nin(int f) { return (f == F_ATAN2 || f == F_POW || f == F_FMOD || f == F_POWF) ? 2 : 1; }
static int nout(int f) { return f == F_SINCOS ? 2 : 1; }

/* one call on raw input bits (floats in the low 32 bits) */
static void eval_raw(int f, uint64_t x, uint64_t y, uint64_t *o0, uint64_t *o1) {
  *o1 = 0;
  switch (f) {
    case F_SIN: *o0 = b_(sin(d_(x))); break;
    case F_COS: *o0 = b_(cos(d_(x))); break;
    case F_SINCOS: { double s, c; sincos(d_(x), &s, &c); *o0 = b_(s); *o1 = b_(c); break; }
    case F_ASIN: *o0 = b_(asin(d_(x))); break;
    case F_ACOS: *o0 = b_(acos(d_(x))); break;
    case F_ATAN2: *o0 = b_(atan2(d_(x), d_(y))); break;
    case F_POW: *o0 = b_(pow_ref(d_(x), d_(y))); break;
    case F_FMOD: *o0 = b_(fmod_ref(d_(x), d_(y))); break;
    case F_SQRT: *o0 = b_(sqrt(d_(x))); break;
    case F_POWF: *o0 = fb_(powf_ref(f_((uint32_t)x), f_((uint32_t)y))); break;
    default: *o0 = fb_(sqrtf(f_((uint32_t)x))); break;
  }
}

static void print_raw(int f, uint64_t x, uint64_t y) {
  uint64_t o0, o1;
  eval_raw(f, x, y, &o0, &o1);
  printf("R %s %llx", NAMES[f], (unsigned long long)x);
  if (nin(f) == 2) printf(" %llx", (unsigned long long)y);
  printf(" %llx", (unsigned long long)o0);
  if (f == F_SINCOS) printf(" %llx", (unsigned long long)o1);
  printf("\n");
}

/* raw special-value records: every special (pair) through the function */
static void specials(int f) {
  int ns = (f == F_POWF || f == F_SQRTF) ? NSF : NSD;
  int two = nin(f) == 2;
  for (int a = 0; a < ns; a++) {
    for (int b = 0; b < (two ? ns : 1); b++) {
      uint64_t x = (ns == NSF) ? special_f(a) : special_d(a);
      uint64_t y = (ns == NSF) ? special_f(b) : special_d(b);
      print_raw(f, x, y);
    }
  }
}

/* hard cases: lines "<func> <hex x> [<hex y>]" (libm_hardcases.txt) */
static void hardcases(const char *path) {
  FILE *in = fopen(path, "r");
  if (!in) { perror(path); exit(1); }
  char name[32];
  unsigned long long x, y;
  char line[256];
  while (fgets(line, sizeof line, in)) {
    if (line[0] == '#' || line[0] == '\n') continue;
    y = 0;
    if (sscanf(line, "%31s %llx %llx", name, &x, &y) < 2) continue;
    for (int f = 0; f < NF; f++)
      if (strcmp(name, NAMES[f]) == 0) print_raw(f, x, y);
  }
  fclose(in);
}

static void rand_seq(const char *label, int doseed, unsigned seedv, int count) {
  if (doseed) srand(seedv);
  uint64_t h = 0;
  int first[8];
  for (int k = 0; k < count; k++) {
    int v = rand();
    if (k < 8) first[k] = v;
    h = hash(h, (uint64_t)(uint32_t)v);
  }
  printf("G %s %d %llx", label, count, (unsigned long long)h);
  for (int k = 0; k < 8; k++) printf(" %x", first[k]);
  printf("\n");
}

int main(int argc, char **argv) {
  uint64_t in[2], out[2];
  if (argc >= 3 && strcmp(argv[1], "-n") == 0) {
    N_PER_FUNC = 1u << atoi(argv[2]);
    argc -= 2;
    argv += 2;
  }
  if (argc == 3 && strcmp(argv[1], "dump") == 0) {
    for (int f = 0; f < NF; f++) {
      if (strcmp(argv[2], NAMES[f]) != 0) continue;
      for (uint64_t i = 0; i < N_PER_FUNC; i++) {
        eval(f, i, in, out);
        fwrite(out, 8, nout(f), stdout);
      }
      return 0;
    }
    return 1;
  }
  __builtin_cpu_init();
  printf("V %s fma=%d avx2=%d\n", gnu_get_libc_version(), __builtin_cpu_supports("fma") ? 1 : 0,
         __builtin_cpu_supports("avx2") ? 1 : 0);
  /* rand() before any srand(): the default state */
  rand_seq("default", 0, 0, 100000);
  static const unsigned SEEDS[] = {0u, 1u, 2u, 42u, 12345u, 0x7fffffffu, 0x80000000u, 0xffffffffu};
  for (int k = 0; k < 8; k++) {
    char label[32];
    snprintf(label, sizeof label, "%x", SEEDS[k]);
    rand_seq(label, 1, SEEDS[k], 10000);
  }
  for (int f = 0; f < NF; f++) {
    printf("N %s %u %u\n", NAMES[f], N_PER_FUNC, CHUNK);
    for (uint64_t c = 0; c < N_PER_FUNC / CHUNK; c++) {
      uint64_t hi = 0, ho = 0;
      for (uint64_t i = c * CHUNK; i < (c + 1) * CHUNK; i++) {
        eval(f, i, in, out);
        for (int k = 0; k < nin(f); k++) hi = hash(hi, in[k]);
        for (int k = 0; k < nout(f); k++) ho = hash(ho, out[k]);
      }
      printf("D %s %llu %llx %llx\n", NAMES[f], (unsigned long long)c, (unsigned long long)hi,
             (unsigned long long)ho);
    }
    specials(f);
  }
  if (argc == 2) hardcases(argv[1]);
  return 0;
}
