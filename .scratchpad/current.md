# Current Work

- PR #14 (`codex/dsp-01`): finish PR-DSP-12 without user checkpoints. PR-DSP-02 through PR-DSP-11 are implemented and locally gated; the latest gate passes host C, 422 shared/102 common tests, both app lint/builds, three NDK ABIs, and ten x86_64 JNI tests. Keep checking GitHub Actions after each push: head `8652364` has no hosted workflow run, so ASan/UBSan remain unverified. Preserve DSP-OFF legacy PCM16 and the two-channel output limit.
