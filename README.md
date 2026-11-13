# minecraft-essential-data

Data for [Prometheus' Essential patch](https://github.com/prometheusreengineering/minecraft-essential), kept up to date
by an hourly job:

- **`cosmetics.json`**: every Essential cosmetic id seen so far. The patch downloads it at startup and asks Essential
  for those ids, so cosmetics keep working after they leave the store. The job adds each new id it sees, and never
  removes one.
- **The `snapshot` branch**: a test fixture for the patch's CI. It holds:
  - `recording.jsonl`: one recorded Essential session, cosmetics side only;
  - `cm-replay.jar`, which serves the recording to test clients on loopback;
  - `manifest.json`: when it was recorded, the Essential version, the run.

  The patch's CI replays it to every Minecraft version and loader it tests, so none of those clients needs an
  account. It is not a service for players.

## How it works

`record.yml` runs every hour, on main only:

1. Resolve the latest Essential release on Modrinth (`scripts/latest-essential.sh`) and fetch its Fabric 1.20.1
   build (`scripts/fetch-essential.sh`).
2. Build `recorder/` and `cm-replay/`, and run their unit tests.
3. Start one game with [Verity](https://github.com/prometheusreengineering/verity): Fabric 1.20.1, plain Essential (no
   Prometheus patch) and the recorder mod, signed in with the test account.
4. In the game, the recorder:
   - accepts Essential's Terms of Service in the sandboxed instance;
   - asks for every id in `cosmetics.json`, as the patch does;
   - waits until the server has answered;
   - attaches Essential's own packet log (`infra-logs`), filtered and neutralized (`recorder/.../Recording.java`).
5. `scripts/check_recording.py` rejects the recording unless it is servable and holds nothing about the account.
6. `scripts/harvest.py` adds new ids to `cosmetics.json` and commits them to main.
7. The recording, `cm-replay.jar` and `manifest.json` replace the single commit on `snapshot`.

**What the recording keeps:** the cosmetics side of the session: cosmetics, categories, types, the store, wardrobe
settings and disabled features.

**What it drops or replaces:**
- Chat, friends, profile, media, skins and everything else are dropped.
- The account's unlocked cosmetics are emptied.
- Its outfits and emote wheels become one blank outfit (default Steve skin) and one blank emote wheel.

## The token

`VERITY_MSA_REFRESH_TOKEN` (from `verity account ci-token <name>`) lives only in the `verity-account` environment,
which is limited to deployment branch `main`. Nothing runs on pull requests except `build.yml`, which has no secrets
and starts no game, so the only code that runs next to the token is code merged to main. Verity never passes the
refresh token itself to the game.

## Locally

```bash
scripts/fetch-essential.sh               # lib/essential.jar (needs jq)
mvn -B package
java -jar verity-cli.jar account login   # once, with the account to record with
java -jar verity-cli.jar run verity-record.json
python3 scripts/check_recording.py verity-report/targets/1.20.1-fabric/attachments/recording.jsonl
java -jar cm-replay/target/cm-replay.jar verity-report/targets/1.20.1-fabric/attachments/recording.jsonl
```

`cm-replay` listens on `ws://127.0.0.1:47801` (Essential's connection manager) and `http://127.0.0.1:47802` (Mojang's
join endpoint, through [jsonproxy](https://github.com/prometheusreengineering/jsonproxy)). See the patch repo's
`verity.json` for how its test clients are pointed at them.


## Disclaimer

This project is intended for educational purposes only. We are not responsible for any damage caused by this project.

Assets belong to their respective owners. This project is not affiliated with Essential or ModCore.

This project was made almost entirely with Claude Opus 5.5 on Medium/High effort.

## License

GPLv3 © Prometheus Reengineering