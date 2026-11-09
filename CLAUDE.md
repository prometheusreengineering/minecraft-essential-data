# CLAUDE.md

Recorder and replay fixture for the Prometheus Essential patch (`prometheusreengineering/minecraft-essential`). Read
`README.md` first.

- **Recording runs on main only.**
  - `record.yml` has no `pull_request` trigger, and its environment (`verity-account`) only deploys main.
  - Never add a trigger or step that runs unmerged code next to `VERITY_MSA_REFRESH_TOKEN`.
  - `build.yml` is the only thing that runs on pull requests: no secrets, no game.
- **The recording is public.** `recorder/.../Recording.java` keeps a narrow allowlist and neutralizes the unlocked,
  outfit and emote-wheel packets. `scripts/check_recording.py` enforces the same rules before publishing. When the
  recording changes, change both, and keep `RecordingTest` covering it.
- **The recording format is the contract with cm-replay**, and both live here.
  - Every line is one of Essential's `infra-logs` JSON lines.
  - Cosmetic requests are answered by id from every recorded `ServerCosmeticsPopulatePacket`.
  - Other requests are matched by name plus canonical JSON (`Session`).
- **Essential versions:** both repos use the latest Essential release (`scripts/latest-essential.sh`), written into the
  profile with jq at run time. The version in `verity-record.json` is only for local runs.
- **What the recorder touches in Essential:** `EssentialTargetsTest` checks every member it uses against the jar's
  bytecode, so update that test with any new one.
  - Essential's `State` types are in elementa, which is nested in Essential's jar and not on the compile classpath,
    so they're read reflectively.
- **Mixin registration:** the recorder registers its mixin like the patch does on Fabric (`RecorderFabric`), because
  Essential's classes load through its own loader.
- **Building locally:** Maven isn't on PATH on the maintainer's machine; use IntelliJ's
  (`"/c/Program Files/JetBrains/IntelliJ IDEA 2025.3/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"`). `jq` isn't
  installed either, so copy an Essential `fabric_1-20-1` jar to `lib/essential.jar` by hand.
- **Code style:** Java 17 in both modules; match the surrounding code; sparse comments that explain why.
