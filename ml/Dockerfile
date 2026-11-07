# syntax=docker/dockerfile:1.4
FROM python:3.11-slim AS runner

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r flyogroup && useradd -r -g flyogroup flyouser

# Copy requirements and install
COPY ml/requirements.txt* /app/
RUN if [ -f /app/requirements.txt ]; then pip install --no-cache-dir -r /app/requirements.txt; else pip install --no-cache-dir fastapi uvicorn pydantic scikit-learn numpy; fi

# Copy ML code and artifacts
COPY ml /app/ml

# Set permissions
RUN chown -R flyouser:flyogroup /app
USER flyouser

ENV PORT=5000 \
    PYTHONUNBUFFERED=1

EXPOSE 5000

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:5000/health || exit 1

ENTRYPOINT ["uvicorn", "ml.service.main:app", "--host", "0.0.0.0", "--port", "5000"]
