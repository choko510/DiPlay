KISS FFT real-transform files copied from https://github.com/mborgerding/kissfft
at commit e5e3fac46e0d94a8f8170c06706b7a4218828333.

Vendored files: kiss_fft.c, kiss_fft.h, kiss_fftr.c, kiss_fftr.h,
_kiss_fft_guts.h, and kiss_fft_log.h. See docs/THIRD_PARTY_NOTICES.md and
docs/licenses/dependencies/KissFFT-COPYING.txt.

The overflow guard in kiss_fft.c also rejects non-positive lengths and casts
the comparison to size_t so GCC/Clang strict warning builds accept the vendored
C11 source.
