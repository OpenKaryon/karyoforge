"""Owned local Jupyter client. JSON lines on stdio are a private IDE protocol."""

from __future__ import annotations

import json
import os
from pathlib import Path
import queue
import sys
import threading
import time

PROTOCOL = 1
MAX_FRAME = 16 * 1024 * 1024
MAX_OUTPUT = 8 * 1024 * 1024
MAX_OUTPUTS = 1000


def main() -> int:
    from jupyter_client import KernelManager
    from jupyter_client.kernelspec import KernelSpec, KernelSpecManager

    token, runtime_path = sys.argv[1:]
    runtime = Path(runtime_path).resolve()
    incoming: queue.Queue = queue.Queue(maxsize=32)

    def emit(event: str, **payload) -> None:
        frame = json.dumps(dict(protocol=PROTOCOL, token=token, event=event, **payload),
                           ensure_ascii=True, allow_nan=False, separators=(',', ':'))
        if len(frame) > MAX_FRAME:
            raise ValueError('Kernel protocol frame exceeds its bound')
        sys.stdout.write(frame + '\n')
        sys.stdout.flush()

    def read_commands() -> None:
        try:
            pending = bytearray()
            while True:
                # Raw descriptor reads avoid a daemon holding Python's buffered stdin lock at exit.
                chunk = os.read(sys.stdin.fileno(), 65536)
                if not chunk:
                    incoming.put({'command': 'shutdown'})
                    return
                pending.extend(chunk)
                if len(pending) > MAX_FRAME and b'\n' not in pending:
                    raise ValueError('IDE command exceeds its frame bound')
                while b'\n' in pending:
                    line, _, rest = pending.partition(b'\n')
                    pending = bytearray(rest)
                    if len(line) > MAX_FRAME:
                        raise ValueError('IDE command exceeds its frame bound')
                    message = json.loads(line)
                    if message.get('protocol') != PROTOCOL or message.get('token') != token:
                        raise ValueError('Invalid IDE protocol identity')
                    incoming.put(message)
                    if message.get('command') == 'shutdown':
                        return
        except Exception:
            incoming.put({'command': 'shutdown'})

    class LocalSpecManager(KernelSpecManager):
        def get_kernel_spec(self, kernel_name):
            if kernel_name != 'karyoforge-local':
                raise ValueError('Only the owned Python kernel is supported')
            return KernelSpec(argv=[sys.executable, '-I', '-m', 'ipykernel_launcher', '-f', '{connection_file}'],
                              display_name='KaryoForge local Python', language='python',
                              interrupt_mode='signal', env={})

    manager = KernelManager(kernel_name='karyoforge-local', kernel_spec_manager=LocalSpecManager(),
                            connection_file=str(runtime / 'connection.json'), ip='127.0.0.1')
    client = None
    active = None
    idle = False
    reply = None
    output_bytes = 0
    output_count = 0
    output_limited = False
    try:
        # No kernelspec registration, server, inherited Jupyter configuration or log pipe.
        environment = dict(os.environ)
        environment.update(IPYTHONDIR=str(runtime / 'ipython'), JUPYTER_CONFIG_DIR=str(runtime / 'config'),
                           JUPYTER_RUNTIME_DIR=str(runtime), PYTHONNOUSERSITE='1')
        manager.start_kernel(cwd=os.getcwd(), env=environment, stdout=-3, stderr=-3)
        client = manager.client()
        client.start_channels()
        client.wait_for_ready(timeout=60)
        threading.Thread(target=read_commands, name='notebook-commands', daemon=True).start()
        emit('ready')
        last_alive = time.monotonic()
        while True:
            try:
                command = incoming.get_nowait()
            except queue.Empty:
                command = None
            if command:
                kind = command.get('command')
                if kind == 'shutdown':
                    break
                if kind == 'execute':
                    if active is not None:
                        raise ValueError('Only one cell may execute at a time')
                    request = command['request']
                    code = command['code']
                    if not isinstance(request, str) or not isinstance(code, str) or len(code) > 1_000_000:
                        raise ValueError('Invalid cell execution request')
                    active = (request, client.execute(code, store_history=True, allow_stdin=False,
                                                      stop_on_error=True))
                    idle, reply = False, None
                    output_bytes, output_count, output_limited = 0, 0, False
                elif kind == 'interrupt' and active:
                    manager.interrupt_kernel()
                elif kind not in ('interrupt',):
                    raise ValueError('Unsupported IDE command')
            if active:
                request, message_id = active
                try:
                    message = client.get_iopub_msg(timeout=0.02)
                except queue.Empty:
                    message = None
                if message and message.get('parent_header', {}).get('msg_id') == message_id:
                    kind, content = message['msg_type'], message['content']
                    if kind == 'status' and content.get('execution_state') == 'idle':
                        idle = True
                    elif kind in ('stream', 'display_data', 'execute_result', 'error'):
                        output = dict(content, output_type=kind)
                        # Transient routing data is not portable output metadata.
                        output.pop('transient', None)
                        encoded = json.dumps(output, ensure_ascii=True, allow_nan=False)
                        output_bytes += len(encoded)
                        output_count += 1
                        if output_bytes <= MAX_OUTPUT and output_count <= MAX_OUTPUTS:
                            emit('output', request=request, output=output)
                        elif not output_limited:
                            emit('output', request=request, output={'output_type': 'stream', 'name': 'stderr',
                                 'text': '[KaryoForge output limit reached; further output is not saved.]\n'})
                            output_limited = True
                try:
                    message = client.get_shell_msg(timeout=0)
                except queue.Empty:
                    message = None
                if message and message.get('parent_header', {}).get('msg_id') == message_id and message['msg_type'] == 'execute_reply':
                    reply = message['content']
                if idle and reply is not None:
                    emit('done', request=request, status=reply.get('status', 'error'), count=reply.get('execution_count'))
                    active = None
            else:
                time.sleep(0.02)
            if time.monotonic() - last_alive > 1:
                if not manager.is_alive():
                    raise RuntimeError('The owned Python kernel exited')
                last_alive = time.monotonic()
    except Exception as failure:
        emit('fatal', message=f'{type(failure).__name__}: {failure}')
        return 1
    finally:
        if client is not None:
            client.stop_channels()
        if manager.has_kernel:
            manager.shutdown_kernel(now=True)
        manager.cleanup_resources()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
