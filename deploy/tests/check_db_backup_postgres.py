"""Disposable primary + streaming replica: local dump, restore, freshness and promotion checks.

Linux: python3 deploy/tests/check_db_backup_postgres.py
Windows: pass --runner-image with Python and Docker Compose installed.
No AWS services or production database are used.
"""
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def run(*command, input=None, timeout=300):
    result = subprocess.run(command, input=input, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(' '.join(command[:4]) + '\n' + (result.stdout + result.stderr).decode(errors='replace')[-5000:])
    return result.stdout.decode().strip()


def wait(command):
    for _ in range(60):
        try:
            return command()
        except RuntimeError:
            time.sleep(1)
    raise RuntimeError('Disposable PostgreSQL primary/replica did not become ready')


def require(value, expected):
    if value != expected:
        raise RuntimeError('Waiting for replica: ' + repr(value))
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runner-image')
    args = parser.parse_args()
    identifier = 'yon-local-backup-check-' + uuid.uuid4().hex[:8]
    image = 'postgres:16-alpine'
    validation = ROOT / '.validation'
    validation.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=identifier, dir=validation) as directory:
        directory = Path(directory)
        assert directory.resolve().parent == validation.resolve()
        network = identifier
        primary, replica, restored, inspector = [identifier + suffix for suffix in ('-primary', '-replica', '-restored', '-files')]
        volumes = [identifier + suffix for suffix in ('-primary-data', '-replica-data', '-state', '-restored-data')]
        try:
            run('docker', 'network', 'create', network)
            compose = {'name': identifier, 'services': {
                'primary': {'image': image, 'container_name': primary,
                    'environment': {'POSTGRES_PASSWORD': 'disposable-test-only', 'POSTGRES_DB': 'yon'},
                    'volumes': [volumes[0]+':/var/lib/postgresql/data'], 'networks': ['backup-test']},
                'postgres-replica': {'image': image, 'container_name': replica,
                    'command': ['postgres', '-c', 'hot_standby_feedback=on', '-c', 'max_standby_streaming_delay=30s'],
                    'environment': {'TZ': 'Asia/Seoul'}, 'cpus': 2, 'mem_limit': '2g',
                    'volumes': [volumes[1]+':/var/lib/postgresql/data'], 'networks': ['backup-test']}},
                'volumes': {v: {'name': v} for v in volumes[:2]},
                'networks': {'backup-test': {'external': True, 'name': network}}}
            compose_file = directory / 'compose.json'
            compose_file.write_text(json.dumps(compose))
            run('docker', 'compose', '-f', str(compose_file), 'up', '-d', 'primary')
            wait(lambda: run('docker', 'exec', primary, 'sh', '-ec', 'test "$(cat /proc/1/comm)" = postgres && pg_isready -U postgres -d yon'))
            def sql(container, statement, user='postgres'):
                return run('docker', 'exec', '-u', 'postgres', container, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', user, '-d', 'yon', '-c', statement)
            wait(lambda: sql(primary, 'SELECT 1'))
            sql(primary, """
                CREATE ROLE application_user LOGIN; CREATE ROLE notification_user LOGIN;
                CREATE SCHEMA application AUTHORIZATION application_user;
                CREATE SCHEMA notification AUTHORIZATION notification_user;
                SET ROLE application_user;
                CREATE TABLE application.raw_external_record(id bigint GENERATED ALWAYS AS IDENTITY, source_id bigint NOT NULL, payload text, PRIMARY KEY(id,source_id)) PARTITION BY LIST(source_id);
                CREATE TABLE application.raw_external_record_youth_policy PARTITION OF application.raw_external_record FOR VALUES IN (1);
                CREATE TABLE application.raw_external_record_employment24 PARTITION OF application.raw_external_record FOR VALUES IN (2);
                CREATE TABLE application.user_behavior_events(id bigint GENERATED ALWAYS AS IDENTITY, received_at timestamp NOT NULL, PRIMARY KEY(id,received_at)) PARTITION BY RANGE(received_at);
                CREATE TABLE application.user_behavior_events_202610 PARTITION OF application.user_behavior_events FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
                INSERT INTO application.raw_external_record(source_id,payload) SELECT 1+(id%2), 'payload-'||id FROM generate_series(1,100) id;
                INSERT INTO application.user_behavior_events(received_at) SELECT '2026-10-27'::timestamp FROM generate_series(1,100);
                GRANT USAGE ON SCHEMA application TO notification_user;
                GRANT SELECT ON application.raw_external_record TO notification_user;
                RESET ROLE; SET ROLE notification_user;
                CREATE TABLE notification.notifications(id bigint PRIMARY KEY, body text);
                INSERT INTO notification.notifications VALUES (1,'notification');
                RESET ROLE;
                CREATE ROLE replicator REPLICATION LOGIN PASSWORD 'disposable-replication-test';
                SELECT pg_create_physical_replication_slot('yon_replica_1');
            """)
            subnet = run('docker', 'network', 'inspect', '--format', '{{(index .IPAM.Config 0).Subnet}}', network)
            ipaddress.ip_network(subnet)
            run('docker', 'exec', '-u', 'postgres', primary, 'sh', '-ec', 'printf "%s\\n" "$1" >> "$PGDATA/pg_hba.conf"', 'sh', 'host replication replicator '+subnet+' scram-sha-256')
            sql(primary, 'SELECT pg_reload_conf()')
            run('docker', 'volume', 'create', volumes[1])
            run('docker', 'run', '--rm', '--network', network, '-v', volumes[1]+':/var/lib/postgresql/data',
                '-e', 'PGPASSWORD=disposable-replication-test', image, 'sh', '-ec',
                'chown postgres:postgres "$PGDATA"; chmod 700 "$PGDATA"; exec su-exec postgres pg_basebackup -h primary -U replicator -D "$PGDATA" -Fp -Xs -R -S yon_replica_1 --checkpoint=fast')
            run('docker', 'compose', '-f', str(compose_file), 'up', '-d', 'postgres-replica')
            wait(lambda: run('docker', 'exec', replica, 'pg_isready', '-U', 'postgres', '-d', 'yon'))
            wait(lambda: require(sql(replica, 'SELECT pg_is_in_recovery()'), 't'))
            sql(primary, "UPDATE application.raw_external_record SET payload='fresh-before-dump' WHERE id=1")
            wait(lambda: require(sql(replica, 'SELECT payload FROM application.raw_external_record WHERE id=1'), 'fresh-before-dump'))
            wait(lambda: require(sql(replica, 'SELECT status FROM pg_stat_wal_receiver'), 'streaming'))
            if args.runner_image:
                inside = PurePosixPath('/workspace') / directory.relative_to(ROOT).as_posix()
                state = '/backup-state'
                run('docker', 'run', '-d', '--name', inspector, '--network', 'none', '-v', volumes[2]+':/state', image, 'sleep', 'infinity')
            else:
                inside, state = directory, str(directory / 'state')
            config = (ROOT / 'deploy/db-replica/backup/backup.ini.example').read_text()
            config = config.replace('REPLACE_WITH_DEPLOYED_RELEASE', 'replica-integration-test')
            config = config.replace('/home/ubuntu/yon-db-replica', str(inside)).replace('/var/lib/yon-backup', state)
            config = config.replace('compose_files=docker-compose.yml', 'compose_files=compose.json').replace('compose_project=yon-db-replica', 'compose_project='+identifier)
            config = config.replace('max_disk_used_percent=80', 'max_disk_used_percent=100')
            filename = directory / 'backup.ini'
            filename.write_text(config)
            def backup(action, expected=0):
                command = ['--config', str(inside / 'backup.ini'), action]
                if args.runner_image:
                    command = ['docker', 'run', '--rm', '--network', 'none',
                        '--mount', 'type=bind,source='+str(ROOT)+',target=/workspace,readonly',
                        '-v', '/var/run/docker.sock:/var/run/docker.sock', '-v', volumes[2]+':/backup-state',
                        args.runner_image, '/workspace/deploy/db-replica/backup/backup.py', *command]
                else:
                    command = [sys.executable, str(ROOT / 'deploy/db-replica/backup/backup.py'), *command]
                result = subprocess.run(command, capture_output=True, timeout=300)
                text = (result.stdout + result.stderr).decode(errors='replace')
                assert result.returncode == expected, text
                return json.loads(result.stdout.decode()) if expected == 0 else text
            snapshot_number = 0
            def snapshot():
                nonlocal snapshot_number
                if not args.runner_image:
                    return Path(state)
                snapshot_number += 1
                exported = directory / ('export-'+str(snapshot_number)); exported.mkdir()
                run('docker', 'cp', inspector+':/state/.', str(exported))
                return exported
            def local_set(result):
                relative = PurePosixPath(result['location']).relative_to(PurePosixPath(state))
                return snapshot().joinpath(*relative.parts)
            # Wrong target must be refused before a dump.
            filename.write_text(config.replace('service=postgres-replica', 'service=primary'))
            assert 'primary' in backup('daily', expected=1)
            filename.write_text(config)
            daily = backup('daily')
            backup('weekly')
            assert backup('monitor')['problems'] == []
            source = local_set(daily)
            manifest = json.loads((source / 'manifest.json').read_text())
            assert manifest['replication_before']['in_recovery'] is True
            assert manifest['replication_before']['wal_receiver_status'] == 'streaming'
            for name, details in manifest['files'].items():
                assert hashlib.sha256((source/name).read_bytes()).hexdigest() == details['sha256']
            run('docker', 'run', '-d', '--name', restored, '--network', 'none',
                '-e', 'POSTGRES_USER=restore_admin', '-e', 'POSTGRES_PASSWORD=disposable-test-only', '-e', 'POSTGRES_DB=yon',
                '-v', volumes[3]+':/var/lib/postgresql/data', image)
            wait(lambda: run('docker', 'exec', restored, 'sh', '-ec', 'test "$(cat /proc/1/comm)" = postgres && pg_isready -U restore_admin -d yon'))
            run('docker', 'exec', '-i', restored, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'restore_admin', '-d', 'yon', input=(source/'globals.sql').read_bytes())
            run('docker', 'exec', '-i', restored, 'pg_restore', '--exit-on-error', '-U', 'restore_admin', '-d', 'yon', input=(source/'database.dump').read_bytes())
            assert sql(restored, 'SELECT count(*) FROM application.raw_external_record') == '100'
            assert sql(restored, 'SELECT count(*) FROM application.user_behavior_events') == '100'
            assert sql(restored, 'SELECT count(*) FROM notification.notifications') == '1'
            assert sql(restored, "SELECT tableowner FROM pg_tables WHERE schemaname='application' AND tablename='raw_external_record'") == 'application_user'
            assert sql(restored, "SELECT has_table_privilege('notification_user','application.raw_external_record','SELECT')") == 't'
            # Loss of upstream must preserve a completed dump, but monitoring must fail.
            run('docker', 'stop', primary)
            wait(lambda: require(sql(replica, "SELECT COALESCE((SELECT status FROM pg_stat_wal_receiver),'')"), ''))
            stale = backup('daily')
            stale_manifest = json.loads((local_set(stale)/'manifest.json').read_text())
            assert stale_manifest['freshness_problems']
            assert 'streaming' in backup('monitor', expected=1)
            run('docker', 'start', primary)
            wait(lambda: require(sql(replica, 'SELECT status FROM pg_stat_wal_receiver'), 'streaming'))
            sql(primary, "UPDATE application.raw_external_record SET payload='fresh-again' WHERE id=1")
            wait(lambda: require(sql(replica, 'SELECT payload FROM application.raw_external_record WHERE id=1'), 'fresh-again'))
            backup('daily')
            assert backup('monitor')['problems'] == []
            # A promoted replica must no longer be accepted as a backup source.
            run('docker', 'exec', '-u', 'postgres', replica, 'pg_ctl', 'promote', '-D', '/var/lib/postgresql/data', '-w')
            assert 'primary' in backup('daily', expected=1)
            print(json.dumps({'postgresql': sql(replica, 'SHOW server_version'),
                'standby_dump_restore': '100 raw / 100 behavior / 1 notification; partitions, owners and grants preserved',
                'disconnected_dump': 'completed; monitor failed', 'reconnected_monitor': 'passed',
                'primary_and_promoted_replica': 'refused', 'storage': 'local only'}, indent=2))
        except Exception:
            for container in (primary, replica, restored):
                result = subprocess.run(['docker','logs','--tail','15',container],capture_output=True)
                print(result.stderr.decode(errors='replace'),file=sys.stderr)
            raise
        finally:
            assert all(name.startswith(identifier) for name in (primary,replica,restored,inspector,*volumes))
            for container in (primary,replica,restored,inspector):
                subprocess.run(['docker','rm','-f',container],capture_output=True)
            for volume in volumes:
                subprocess.run(['docker','volume','rm',volume],capture_output=True)
            subprocess.run(['docker','network','rm',network],capture_output=True)


if __name__ == '__main__':
    main()
