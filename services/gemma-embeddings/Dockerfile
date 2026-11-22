FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir torch==2.11.0 torchvision==0.26.0 --index-url "$TORCH_INDEX_URL" \
    && pip install --no-cache-dir -r requirements.txt
COPY app.py .
ENV HF_HOME=/cache/huggingface
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
