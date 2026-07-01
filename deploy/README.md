# Deploy cljot-crud on a new server (without CapRover)

Self-contained stack to run the `cljot-crud` Telegram bot on a plain server:
**PostgreSQL** (bot DB) + **SeaweedFS** (S3 storage for videos, a MinIO replacement)
+ the **bot** (built from this repo's sources).

```
deploy/
├── docker-compose.yml   postgres:16 + seaweedfs + bot (+ tools: db-restore, s3-upload)
├── Dockerfile           multi-stage build of the bot uberjar (build context = repo root)
├── setup.sh             idempotent "one command" bring-up
├── .env.example         config template  -> copy to .env
├── seaweed/
│   └── s3.json.template  S3 identities (setup.sh renders seaweed/s3.json from .env)
├── .gitignore           ignores .env, seaweed/s3.json, data/
└── data/                (NOT in git) migrated content, placed here before setup
    ├── dumps/cljot_default.sql
    └── objects/            *.mp4 / *.MOV
```

The migrated **data is intentionally not in git** (it contains real user data and
~313 MB of private videos). You transfer it onto the new server separately — see
[Transferring the data](#transferring-the-data-over-ssh) below.

## Prerequisites (new server)
- Docker + Docker Compose v2 (`docker compose version`).
- ~1.5 GB free disk (images + data).
- Outbound access to `api.telegram.org`. The bot runs in **long-polling** mode
  (`SELF_URL` unset), so **no public URL / TLS / open inbound port** is required.

## Transferring the data over SSH

The extracted content is a directory with two parts:

```
data-export/
├── dumps/cljot_default.sql     # 42 menu rows, 3 users, migrations
└── objects/                    # 36 video files (~313 MB)
```

Pick one of the two transfer paths.

### Path A — via your local machine (download, then upload)
Run these on your **local machine**. Replace the placeholders with your SSH details.

```bash
# 1. Download from the OLD server to local
rsync -avP OLD_USER@OLD_HOST:/root/cljot-migration/data-export/ ./cljot-data/

# 2. Get the repo on the NEW server (clone, or rsync your local checkout)
ssh NEW_USER@NEW_HOST 'git clone <REPO_URL> ~/cljot-db'   # or scp/rsync the repo

# 3. Upload the data into deploy/data/ on the NEW server
rsync -avP ./cljot-data/ NEW_USER@NEW_HOST:~/cljot-db/deploy/data/
```

### Path B — directly old → new (if the servers can reach each other)
```bash
# run on the OLD server (or via ssh)
rsync -avP /root/cljot-migration/data-export/ NEW_USER@NEW_HOST:~/cljot-db/deploy/data/
```

After transfer, the new server must have:
```
~/cljot-db/deploy/data/dumps/cljot_default.sql
~/cljot-db/deploy/data/objects/<36 files>
```

> Tip: a single-file alternative is a tarball. On the old server the export is also
> packed as `/root/cljot-migration/cljot-data.tar.gz`. Download it with
> `scp OLD_USER@OLD_HOST:/root/cljot-migration/cljot-data.tar.gz .`,
> then `tar xzf cljot-data.tar.gz` and upload the resulting `dumps/` and `objects/`
> into `deploy/data/`.

## Deploy

From `deploy/` on the new server:

```bash
cp .env.example .env
nano .env          # fill in the values (see below), then:
./setup.sh
docker compose logs -f bot
```

`setup.sh` renders the S3 credentials, starts postgres + seaweedfs, restores the DB
(only if empty), uploads the 36 videos into the bucket, then builds and starts the bot.
It is idempotent — safe to re-run.

### What to put in `.env`
- `TELEGRAM_BOT_TOKEN` — **a fresh token from @BotFather** (the old one lived in
  CapRover env and is gone; `/revoke` → `/token` keeps the same @username and chats).
- `TELEGRAM_ADMIN_IDS` — admin user IDs, comma-separated. Restored users:
  `108673844` (Daria/porkygy), `112789249` (Artem/markov_artem), `5804239176` (Sergei).
- `INVITE` — invite code; an anonymous user registers with `/start <INVITE>`.
- `DB_PASSWORD`, `MINIO_USER`, `MINIO_PASSWORD` — set your own (no quotes / backslashes).

## Verify

```bash
docker compose exec postgres psql -U "$DB_USER" -d "$DB_NAME" -c 'select count(*) from file'    # 42
docker compose exec postgres psql -U "$DB_USER" -d "$DB_NAME" -c 'select id, username from tg_user'  # 3
docker compose run --rm --entrypoint rclone s3-upload ls "s3:$MINIO_BUCKET" | wc -l              # 36
```

## Notes
- **MinIO replacement.** SeaweedFS exposes an S3 API (path-style) with "one root key
  creates buckets" semantics, so no bot code changes are needed: the bot auto-creates
  the `MINIO_BUCKET` on first start. `MINIO_USER`/`MINIO_PASSWORD` are both the
  SeaweedFS root credentials and the bot's S3 credentials.
- **File coverage.** The menu has 42 rows, but only 36 have a stored video
  (`storage_key` set). The other 6 were served via Telegram `copy-message` and never
  had an object — this matches the old behaviour.
- **Postgres 16.** Data was captured from PG 14; the plain-SQL dump restores into PG 16
  cleanly. Migrations are already in the dump, so migratus won't re-run them.
