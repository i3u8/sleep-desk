# On-device sound model

YAMNet is used for acoustic suggestions, not sleep staging or health diagnosis.
No user audio is bundled with this model.

- Source repository: tensorflow/tflite-support
- Pinned revision: 522a02b47444e6016d5a0d4d1388b522bc2529f2
- Source model: tensorflow_lite_support/metadata/python/tests/testdata/audio_classifier/yamnet_tfhub.tflite
- Source labels: same directory, yamnet_521_labels.txt
- Model SHA-256: 141fba1cdaae842c816f28edc4937e8b4f0af4c8df21862ccc6b52dc567993c3
- Model input: mono 16 kHz float waveform, PCM16 scaled by 32768.
- Inference patches: 15600 samples, 7680 sample stride; final padding is recorded
  by the valid frame interval and is not treated as recorded post-roll.
- Model scores are not calibrated probabilities of events or clinical risks.

The application does not use the test metadata JSON from the source directory.
Its example metadata settings are not the model input contract.
The corresponding repository license is included as LICENSE-yamnet.txt.
