FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY partition_interpreter.py text_overlay.py audio_engine.py render_pipeline.py worker_daemon.py ./

ENV PYTHONUNBUFFERED=1
EXPOSE 8080

# Default: HTTP worker mode (Cloud Run / Render / Cloudflare Containers)
# Override CMD to ["python3", "worker_daemon.py", "--mode", "pull"] for Oracle Always Free pull daemon.
CMD ["python3", "worker_daemon.py", "--mode", "http", "--port", "8080"]
