FROM python:3.14-alpine
WORKDIR /app
COPY experiments/mock-partner-api/server.py server.py
CMD ["python", "server.py", "--host", "0.0.0.0", "--database", "/evidence/external.sqlite"]
