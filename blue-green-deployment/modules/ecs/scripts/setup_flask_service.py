import os

service_file = "/etc/systemd/system/flaskapp.service"

service_content = """[Unit]
Description=Flask App
After=network.target

[Service]
User=ec2-user
WorkingDirectory=/home/ec2-user
ExecStart=/usr/bin/python3 /home/ec2-user/app.py
Restart=always

[Install]
WantedBy=multi-user.target
"""

with open(service_file, "w") as f:
    f.write(service_content)

os.system("sudo systemctl daemon-reload")
os.system("sudo systemctl enable flaskapp")
os.system("sudo systemctl restart flaskapp")
