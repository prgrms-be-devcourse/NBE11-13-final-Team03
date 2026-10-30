"""Linux unit checks for replica-only local backups; no PostgreSQL or network required."""
import fcntl
import importlib.util
import json
from datetime import datetime, timezone, timedelta
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('backup', ROOT / 'deploy/db-replica/backup/backup.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        example = (ROOT / 'deploy/db-replica/backup/backup.ini.example').read_text()
        example = example.replace('REPLACE_WITH_DEPLOYED_RELEASE', 'test-release')
        example = example.replace('/home/ubuntu/yon-db-replica', str(self.root)).replace('/var/lib/yon-backup', str(self.root / 'state'))
        self.filename = self.root / 'backup.ini'
        self.filename.write_text(example)
        self.backup = MODULE.Backup(self.filename)
        self.status = {'in_recovery': True, 'checked_at': '2026-10-27T11:00:00+09:00',
                       'replay_timestamp': '2026-10-27T10:59:59+09:00', 'replay_age_seconds': 1,
                       'wal_receiver_status': 'streaming', 'last_msg_receipt_time': '2026-10-27T11:00:00+09:00',
                       'last_msg_age_seconds': 1, 'receive_lsn': '0/3000000', 'replay_lsn': '0/3000000',
                       'replay_lag_bytes': 0, 'replay_paused': False}
        self.commands = []
        self.dump_calls = 0
        self.dump_failures = 0
        self.error = b'ERROR: canceling statement due to conflict with recovery'
        self.promote_during_dump = False
        self.addCleanup(patch.stopall)
        patch.object(self.backup, 'run', side_effect=self.run_command).start()
        self.space = patch.object(self.backup, 'disk_usage', return_value={'backup_percent': 25, 'database_percent': 25}).start()
        patch.object(MODULE.time, 'sleep').start()

    def run_command(self, command, *, output=None, input_file=None, timeout=None):
        self.commands.append(command)
        if 'psql' in command:
            return '16.15' if 'SHOW server_version' in command else json.dumps(self.status)
        if 'pg_dump' in command:
            self.dump_calls += 1
            if self.dump_calls <= self.dump_failures:
                if output:
                    output.write(b'partial')
                raise MODULE.CommandError('dump failed', self.error)
            if self.promote_during_dump:
                self.status['in_recovery'] = False
        if output:
            output.write(b'roles without passwords' if 'pg_dumpall' in command else b'PGDMP-test-data')
        return ''

    def latest(self, kind='daily'):
        directory = Path(self.backup.read_state(kind)['location'])
        return directory, json.loads((directory / 'manifest.json').read_text())

    def seed(self, kind, number):
        directory = self.backup.category(kind) / ('202610%02dT000000Z-%08x' % (number, number))
        directory.mkdir()
        manifest = {'kind': kind, 'completed_at': (datetime.now(timezone.utc) - timedelta(days=20-number)).isoformat(),
                    'freshness_problems': [], 'files': {}}
        for name in MODULE.FILES:
            path = directory / name
            path.write_bytes(b'old backup')
            manifest['files'][name] = {'bytes': path.stat().st_size, 'sha256': self.backup.digest(path)}
        self.backup.write_json(directory / 'manifest.json', manifest)
        return directory

    def test_complete_set_contains_verified_files_and_replication_metadata(self):
        self.assertEqual(0, self.backup.execute('daily'))
        directory, manifest = self.latest()
        self.backup.verify_files(directory, manifest)
        self.assertEqual('streaming', manifest['replication_before']['wal_receiver_status'])
        self.assertEqual(self.status['replay_timestamp'], manifest['replication_after']['replay_timestamp'])
        self.assertEqual('test-release', manifest['release_reference'])
        self.assertEqual('OK', self.backup.read_state('attempt')['status'])
        self.assertEqual(set(MODULE.FILES) | {'manifest.json'}, {p.name for p in directory.iterdir()})

    def test_primary_is_rejected_without_dump_or_retention(self):
        self.status['in_recovery'] = False
        with self.assertRaisesRegex(RuntimeError, 'primary'):
            self.backup.execute('daily')
        self.assertEqual(0, self.dump_calls)
        self.assertFalse(self.backup.read_state('latest-success'))

    def test_promotion_during_dump_is_rejected(self):
        self.promote_during_dump = True
        with self.assertRaisesRegex(RuntimeError, 'primary'):
            self.backup.execute('daily')
        self.assertFalse(list(self.backup.category('daily').glob('*/manifest.json')))

    def test_recovery_conflict_retries_once_and_discards_partial_attempt(self):
        self.dump_failures = 1
        self.assertEqual(0, self.backup.execute('daily'))
        self.assertEqual(2, self.dump_calls)
        directory, manifest = self.latest()
        self.assertEqual(2, manifest['dump_attempts'])
        self.assertEqual(b'PGDMP-test-data', (directory / 'database.dump').read_bytes())
        self.assertEqual(1, len(list(directory.parent.iterdir())))

    def test_second_conflict_fails_without_completion_marker(self):
        self.dump_failures = 10
        with self.assertRaises(MODULE.CommandError):
            self.backup.execute('daily')
        self.assertEqual(2, self.dump_calls)
        self.assertFalse(list(self.backup.category('daily').iterdir()))
        self.assertEqual('FAILED', self.backup.read_state('attempt')['status'])

    def test_other_command_errors_are_not_retried(self):
        self.dump_failures, self.error = 1, b'permission denied'
        with self.assertRaises(MODULE.CommandError):
            self.backup.execute('daily')
        self.assertEqual(1, self.dump_calls)

    def test_disk_at_80_percent_skips_dump_and_keeps_existing_sets(self):
        existing = [self.seed('daily', number) for number in range(1, 9)]
        self.space.return_value = {'backup_percent': 80, 'database_percent': 25}
        with self.assertRaisesRegex(RuntimeError, '디스크'):
            self.backup.execute('daily')
        self.assertEqual(0, self.dump_calls)
        self.assertTrue(all(p.exists() for p in existing))

    def test_database_disk_limit_also_blocks_dump(self):
        self.space.return_value = {'backup_percent': 25, 'database_percent': 80}
        with self.assertRaisesRegex(RuntimeError, '디스크'):
            self.backup.execute('daily')
        self.assertEqual(0, self.dump_calls)

    def test_disk_filling_during_dump_does_not_publish_set(self):
        self.space.side_effect = [{'backup_percent': 25, 'database_percent': 25}, {'backup_percent': 81, 'database_percent': 25}]
        with self.assertRaisesRegex(RuntimeError, '디스크'):
            self.backup.execute('daily')
        self.assertFalse(list(self.backup.category('daily').iterdir()))

    def test_retention_keeps_7_daily_and_4_weekly_complete_sets(self):
        daily = [self.seed('daily', number) for number in range(1, 9)]
        weekly = [self.seed('weekly', number) for number in range(1, 6)]
        self.backup.prune('daily'); self.backup.prune('weekly')
        self.assertFalse(daily[0].exists()); self.assertFalse(weekly[0].exists())
        self.assertEqual(7, sum(p.exists() for p in daily))
        self.assertEqual(4, sum(p.exists() for p in weekly))

    def test_incomplete_corrupt_and_unrelated_directories_are_not_deleted(self):
        for number in range(1, 9): self.seed('daily', number)
        base = self.backup.category('daily')
        incomplete = base / '.incomplete-interrupted'; incomplete.mkdir()
        corrupt = base / '20261001T000000Z-ffffffff'; corrupt.mkdir(); (corrupt / 'manifest.json').write_text('{')
        unrelated = base / 'keep-me'; unrelated.mkdir()
        self.backup.prune('daily')
        self.assertTrue(all(p.exists() for p in (incomplete, corrupt, unrelated)))

    def test_symlink_category_is_rejected(self):
        outside = self.root / 'other'; outside.mkdir()
        (self.backup.state / 'daily').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, '폴더 밖'):
            self.backup.execute('daily')
        self.assertEqual([], list(outside.iterdir()))

    def test_checksum_detects_same_size_corruption(self):
        self.backup.execute('daily')
        directory, manifest = self.latest()
        path = directory / 'database.dump'
        path.write_bytes(b'X' + path.read_bytes()[1:])
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            self.backup.verify_files(directory, manifest)

    def test_sunday_weekly_copy_uses_one_dump_and_independent_files(self):
        self.backup.execute('daily', include_weekly=True)
        daily, first = self.latest(); weekly, second = self.latest('weekly')
        self.assertEqual(1, self.dump_calls)
        self.assertEqual(first['files'], second['files'])
        (daily / 'database.dump').unlink()
        self.backup.verify_files(weekly, second)

    def test_disconnected_replica_is_backed_up_and_monitor_fails(self):
        self.status['wal_receiver_status'] = None
        self.assertEqual(0, self.backup.execute('daily', include_weekly=True))
        self.assertTrue(self.latest()[1]['freshness_problems'])
        self.assertEqual(1, self.backup.monitor())
        self.assertTrue(any('streaming' in p for p in self.backup.read_state('monitor')['problems']))

    def test_idle_replica_with_old_replay_timestamp_is_healthy(self):
        self.status['replay_age_seconds'] = 86400
        self.backup.execute('daily', include_weekly=True)
        self.assertEqual(86400, self.latest()[1]['replication_before']['replay_age_seconds'])
        self.assertEqual([], self.latest()[1]['freshness_problems'])
        self.assertEqual(0, self.backup.monitor())

    def test_missing_replay_timestamp_still_allows_dump(self):
        self.status.update(replay_timestamp=None, replay_age_seconds=None)
        self.assertEqual(0, self.backup.execute('daily', include_weekly=True))
        self.assertIsNone(self.latest()[1]['replication_before']['replay_timestamp'])
        self.assertEqual(0, self.backup.monitor())

    def test_wal_message_age_boundary_missing_and_invalid_values(self):
        for age, healthy in ((59.9, True), (60, False), (61, False), (None, False), (-1, False)):
            with self.subTest(age=age):
                self.status['last_msg_age_seconds'] = age
                self.assertEqual(not healthy, bool(self.backup.freshness_problems(self.status)))

    def test_old_configuration_uses_default_message_age_without_replay_age_check(self):
        self.backup.config['monitor'].pop('max_wal_message_age_seconds')
        self.backup.config['monitor']['max_replay_age_seconds'] = '600'
        self.status['replay_age_seconds'] = 86400
        self.assertEqual([], self.backup.freshness_problems(self.status))
        self.status['last_msg_age_seconds'] = 60
        self.assertTrue(self.backup.freshness_problems(self.status))

    def test_paused_replay_fails_even_with_recent_messages(self):
        self.status['replay_paused'] = True
        self.backup.execute('daily', include_weekly=True)
        self.assertEqual(1, self.backup.monitor())

    def test_stale_dump_stays_unhealthy_until_replaced(self):
        self.status['last_msg_age_seconds'] = 60
        self.backup.execute('daily', include_weekly=True)
        self.status['last_msg_age_seconds'] = 1
        self.assertEqual(1, self.backup.monitor())
        self.backup.execute('daily', include_weekly=True)
        self.assertEqual(0, self.backup.monitor())

    def test_old_snapshot_remains_unhealthy_after_receiver_recovers(self):
        self.status['wal_receiver_status'] = None
        self.backup.execute('daily', include_weekly=True)
        self.status['wal_receiver_status'] = 'streaming'
        self.assertEqual(1, self.backup.monitor())
        self.backup.execute('daily', include_weekly=True)
        self.assertEqual(0, self.backup.monitor())

    def test_missing_old_backup_or_missing_file_fails_monitor(self):
        self.assertEqual(1, self.backup.monitor())
        self.backup.execute('daily', include_weekly=True)
        self.assertEqual(0, self.backup.monitor())
        self.backup.write_state('daily', dict(self.backup.read_state('daily'), completed_at=time.time()-31*3600))
        self.assertEqual(1, self.backup.monitor())
        self.latest('weekly')[0].joinpath('globals.sql').unlink()
        self.assertEqual(1, self.backup.monitor())

    def test_concurrent_backup_does_not_start_second_dump(self):
        with (self.backup.state / 'backup.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertEqual(1, self.backup.execute('daily'))
        self.assertEqual(0, self.dump_calls)

    def test_command_error_keeps_private_diagnostics(self):
        result = subprocess.CompletedProcess(['docker'], 1, stdout=b'', stderr=b'private-detail')
        with patch.object(MODULE.subprocess, 'run', return_value=result):
            with self.assertRaises(MODULE.CommandError) as error:
                MODULE.Backup.run(self.backup, ['docker', 'compose'])
        self.assertNotIn('private-detail', str(error.exception))
        self.assertEqual(b'private-detail', (self.backup.state / 'last-command-error.log').read_bytes())

    def test_sunday_schedule_adds_weekly_set(self):
        with patch.object(MODULE, 'Backup', return_value=self.backup), patch.object(MODULE, 'datetime') as clock, \
             patch.object(self.backup, 'execute', return_value=0) as execute, patch('sys.argv', ['backup.py', 'scheduled']):
            clock.now.return_value = datetime(2026, 11, 1, tzinfo=timezone.utc)
            self.assertEqual(0, MODULE.main())
            execute.assert_called_once_with('daily', include_weekly=True)


if __name__ == '__main__':
    unittest.main()
