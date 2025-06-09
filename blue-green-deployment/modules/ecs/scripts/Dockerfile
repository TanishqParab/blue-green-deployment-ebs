FROM python:3.9-slim

WORKDIR /app

ARG APP_NAME=1
COPY app_${APP_NAME}.py app.py

RUN pip install flask

EXPOSE 80

CMD ["python", "app.py"]