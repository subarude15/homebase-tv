FROM python:3.12-slim

WORKDIR /app
COPY pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir .

ENV HOMEBASE_DATA_DIR=/data
VOLUME /data
EXPOSE 8770

HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8770/health')"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8770"]
