"""Add parent directory entries for older Forge extractors without changing file contents.

Usage: python deploy/repair-assets-zip-directories.py input.zip output.zip
The input is immutable, and an existing output or partial file is never overwritten.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile


def repair(source: Path, output: Path):
    source = source.resolve(strict=True)
    output = output.resolve()
    partial = output.with_name(output.name + '.partial')
    if source == output or output.exists() or partial.exists():
        raise ValueError('Source, existing output and partial files must not be overwritten')
    directories = set()
    files = 0
    payload_hashes = {}
    with zipfile.ZipFile(source) as original:
        names = set()
        for entry in original.infolist():
            # On Windows ZipInfo normalizes backslashes and truncates at NUL.
            # Validate the original name before trusting the normalized path.
            if entry.orig_filename != entry.filename:
                raise ValueError('Archive entry name was normalized: ' + repr(entry.orig_filename))
            path = PurePosixPath(entry.filename)
            if (not entry.filename or path.is_absolute() or '..' in path.parts
                    or '\\' in entry.filename or ':' in entry.filename
                    or '\x00' in entry.filename or entry.filename in names):
                raise ValueError('Unsafe or duplicate archive entry: ' + entry.filename)
            names.add(entry.filename)
            if entry.flag_bits & 1:
                raise ValueError('Encrypted entries are not supported')
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(partial, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=1) as fixed:
            for entry in original.infolist():
                path = PurePosixPath(entry.filename)
                parents = list(reversed(path.parents))
                if entry.is_dir():
                    parents.append(path)
                for parent in parents:
                    name = parent.as_posix()
                    if name == '.':
                        continue
                    if name in names:
                        raise ValueError('File/directory collision: ' + name)
                    name += '/'
                    if name in directories:
                        continue
                    info = zipfile.ZipInfo(name, entry.date_time)
                    info.external_attr = (0o40755 << 16) | 0x10
                    fixed.writestr(info, b'')
                    directories.add(name)
                if entry.is_dir():
                    continue
                digest = hashlib.sha256()
                info = copy.copy(entry)
                # Preserve format and file metadata; only add explicit parent folders.
                info.compress_type = zipfile.ZIP_DEFLATED
                info._compresslevel = 1
                with original.open(entry) as src, fixed.open(info, 'w') as dst:
                    while data := src.read(1024 * 1024):
                        digest.update(data)
                        dst.write(data)
                payload_hashes[entry.filename] = digest.hexdigest()
                files += 1
        # Every payload must survive recompression unchanged before publishing the result.
        with zipfile.ZipFile(partial) as fixed:
            actual = {}
            for entry in fixed.infolist():
                if entry.is_dir():
                    continue
                with fixed.open(entry) as stream:
                    actual[entry.filename] = hashlib.file_digest(stream, 'sha256').hexdigest()
            if actual != payload_hashes:
                raise ValueError('Payloads changed during repacking')
    partial.rename(output)
    with output.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'source': str(source), 'output': str(output), 'files': files,
            'directories': len(directories), 'size': output.stat().st_size,
            'sha256': digest, 'payloadsUnchanged': True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    print(json.dumps(repair(args.source, args.output), ensure_ascii=False))
