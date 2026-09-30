"""YT Quay's bundled yt-dlp postprocessor (loaded only from the app's plugin dir).

Original preserves audio-only files byte for byte, or stream-copies the first audio
track from a video. Manual modes always encode at the selected bitrate target;
AAC and Opus are variable bitrate and their measured bitrate can differ.
"""

import os
import shutil
import tempfile

from yt_dlp.postprocessor.ffmpeg import FFmpegPostProcessor
from yt_dlp.utils import PostProcessingError, replace_extension

__all__ = ['QuayAudioPP']


class QuayAudioPP(FFmpegPostProcessor):
    _BITRATES = {
        'mp3': ('64', '96', '128', '160', '192', '256', '320'),
        'm4a': ('64', '96', '128', '160', '192', '256'),
        'opus': ('48', '64', '96', '128', '160', '192', '256'),
    }
    _DEFAULT_BITRATES = {'mp3': '192', 'm4a': '192', 'opus': '128'}
    _ENCODERS = {'mp3': 'libmp3lame', 'm4a': 'aac', 'opus': 'libopus'}
    _CODECS = {'mp3': 'mp3', 'm4a': 'aac', 'opus': 'opus'}
    _COPY_EXTENSIONS = {
        'aac': 'm4a', 'alac': 'm4a', 'mp3': 'mp3', 'opus': 'opus',
        'vorbis': 'ogg', 'flac': 'flac',
    }

    def __init__(self, downloader=None, format='original', bitrate=None):
        # Do not normalize strings: reject whitespace, suffixes, floats and values
        # outside the UI's exact allowlist, including direct CLI/plugin calls.
        if not isinstance(format, str) or format not in ('original', *self._BITRATES):
            raise PostProcessingError('QuayAudio: format must be original, mp3, m4a or opus')
        if format == 'original':
            if bitrate is not None:
                raise PostProcessingError('QuayAudio: original audio cannot specify a bitrate')
        else:
            if bitrate is None:
                bitrate = self._DEFAULT_BITRATES[format]
            if not isinstance(bitrate, str) or bitrate not in self._BITRATES[format]:
                allowed = ', '.join(self._BITRATES[format])
                raise PostProcessingError(f'QuayAudio: {format} bitrate must be one of {allowed} kbps')
        super().__init__(downloader)
        self._format = format
        self._bitrate = bitrate

    @staticmethod
    def _audio_streams(metadata):
        return [s for s in metadata.get('streams', []) if s.get('codec_type') == 'audio']

    @staticmethod
    def _has_video(metadata):
        return any(
            s.get('codec_type') == 'video'
            and s.get('disposition', {}).get('attached_pic') not in (1, '1')
            for s in metadata.get('streams', []))

    def _probe(self, path, *, output=False):
        metadata = self.get_metadata_object(path, opts=['-v', 'error', *(['-count_packets'] if output else [])])
        if not isinstance(metadata, dict) or metadata.get('error'):
            raise PostProcessingError('QuayAudio: could not inspect the audio file with ffprobe')
        return metadata

    @classmethod
    def _copy_extension(cls, codec):
        if codec.startswith('pcm_'):
            return 'wav'
        return cls._COPY_EXTENSIONS.get(codec, 'mka')

    def _output_options(self, audio):
        opts = ['-map', '0:a:0', '-vn', '-sn', '-dn']
        if self._format == 'original':
            opts += ['-c:a', 'copy']
            if audio.get('codec_name') == 'alac':
                # Matroska can omit ALAC packet durations. MP4's automatic edit
                # list then hides the last packet; disabling it keeps all audio.
                opts += ['-use_editlist', '0']
            return opts
        opts += ['-c:a', self._ENCODERS[self._format], '-b:a', f'{self._bitrate}k', '-ar', '48000']
        if self._format == 'mp3' and int(audio.get('channels') or 0) > 2:
            opts += ['-ac', '2']
        if self._format == 'opus':
            opts += ['-vbr', 'on']
        return opts

    @staticmethod
    def _temporary_path(source, extension, *, original=False):
        fd, path = tempfile.mkstemp(
            prefix='.quay-original-' if original else '.quay-audio-',
            suffix=f'.{extension}', dir=os.path.dirname(os.path.abspath(source)))
        os.close(fd)
        return path

    @staticmethod
    def _discard(path):
        if path:
            try:
                os.unlink(path)
            except OSError:
                pass

    @staticmethod
    def _metadata_updates(path, extension, metadata, audio):
        bitrate = audio.get('bit_rate')
        updates = {
            'filepath': path,
            'ext': extension,
            'format': extension,
            'vcodec': 'none',
            'acodec': audio['codec_name'],
            'abr': float(bitrate) / 1000 if bitrate else None,
            'tbr': float(bitrate) / 1000 if bitrate else None,
            'asr': int(audio['sample_rate']) if audio.get('sample_rate') else None,
            'audio_channels': audio.get('channels'),
            'width': None, 'height': None, 'fps': None, 'vbr': None,
            'resolution': 'audio only',
            'filesize_approx': None,
        }
        duration = audio.get('duration') or metadata.get('format', {}).get('duration')
        if duration is not None:
            updates['duration'] = float(duration)
        return updates

    def run(self, information):
        temporary = backup = None
        installed = False
        try:
            source = information['filepath']
            metadata = self._probe(source)
            audio_streams = self._audio_streams(metadata)
            if not audio_streams:
                raise PostProcessingError('QuayAudio: source contains no audio stream')
            audio = audio_streams[0]
            codec = audio.get('codec_name')
            if not isinstance(codec, str) or not codec:
                raise PostProcessingError('QuayAudio: could not determine the source audio codec')

            if self._format == 'original' and not self._has_video(metadata):
                updates = self._metadata_updates(source, information['ext'], metadata, audio)
                updates['filesize'] = os.path.getsize(source)
                information.update(updates)
                self.to_screen('Keeping original audio without changing the file')
                return [], information

            extension = self._copy_extension(codec) if self._format == 'original' else self._format
            destination = replace_extension(source, extension, information.get('ext'))
            temporary = self._temporary_path(source, extension)
            self.to_screen(f'Destination: {destination}')
            # Manual modes deliberately never take a same-codec stream-copy
            # shortcut: -b:a must reach an actual encoder on every invocation.
            self.run_ffmpeg(source, temporary, self._output_options(audio))
            output = self._probe(temporary, output=True)
            output_audio = self._audio_streams(output)
            expected_codec = codec if self._format == 'original' else self._CODECS[self._format]
            if (os.path.getsize(temporary) == 0 or len(output_audio) != 1
                    or self._has_video(output) or output_audio[0].get('codec_name') != expected_codec
                    or int(output_audio[0].get('nb_read_packets') or 0) <= 0):
                raise PostProcessingError('QuayAudio: converted output failed audio validation')

            # Prepare all potentially failing metadata reads before installing.
            updates = self._metadata_updates(destination, extension, output, output_audio[0])
            updates['filesize'] = os.path.getsize(temporary)
            if information.get('filetime') is not None:
                self.try_utime(temporary, information['filetime'], information['filetime'])
            deletion = source
            if os.path.abspath(source) == os.path.abspath(destination):
                backup = self._temporary_path(source, information['ext'], original=True)
                # Copy before the atomic replacement. Unlike renaming the source
                # away, this also leaves its exact path intact if installation
                # fails. The backup is returned to yt-dlp, never the final path.
                shutil.copy2(source, backup)
                deletion = backup
            os.replace(temporary, destination)
            installed = True
            information.update(updates)
            return [deletion], information
        except PostProcessingError:
            raise
        except Exception as error:
            raise PostProcessingError(f'QuayAudio: audio processing failed: {error}') from error
        finally:
            self._discard(temporary)
            if not installed:
                self._discard(backup)
