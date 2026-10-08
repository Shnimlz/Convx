"""Execute the actual Room offline song queries against SQLite fixtures."""
import re
import sqlite3
import unittest
from pathlib import Path

DAO = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/com/convx/music/db/DatabaseDao.kt'

class LocalOnlyQueriesTest(unittest.TestCase):
    def test_completed_downloads_and_device_files_only(self):
        db = sqlite3.connect(':memory:')
        db.execute('CREATE TABLE song (id TEXT, isLocal INTEGER, isDownloaded INTEGER, title TEXT, inLibrary INTEGER, dateDownload INTEGER, totalPlayTime INTEGER)')
        db.executemany('INSERT INTO song VALUES (?, ?, ?, ?, ?, ?, ?)', [
            ('file', 1, 0, 'Song file', 1, None, 0),
            ('download', 0, 1, 'Song downloaded', None, 2, 0),
            ('flac', 0, 1, 'Song FLAC', 1, 3, 0),
            ('remote', 0, 0, 'Song remote', 1, None, 0),
            ('partial', 0, 0, 'Song incomplete', 1, None, 0),
        ])
        queries = re.findall(r'@Query\("([^"\n]+)"\)\s+fun localSongsBy\w+Asc\(includeDownloads', DAO.read_text())
        self.assertEqual(len(queries), 4)
        for query in queries:
            with self.subTest(query=query):
                self.assertEqual({row[0] for row in db.execute(query, {'includeDownloads': True})}, {'file', 'download', 'flac'})
                self.assertEqual({row[0] for row in db.execute(query, {'includeDownloads': False})}, {'file'})
        db.execute("UPDATE song SET isDownloaded = 0 WHERE id = 'download'")
        self.assertNotIn('download', {row[0] for row in db.execute(queries[0], {'includeDownloads': True})})
        db.close()

if __name__ == '__main__':
    unittest.main()
