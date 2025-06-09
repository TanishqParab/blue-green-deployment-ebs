import os
import sys

# Get the app name from command line argument
app_name = sys.argv[1] if len(sys.argv) > 1 else "default"

service_file = f"/etc/systemd/system/flask-app-{app_name}.service"

service_content = f"""[Unit]
Description=Flask App for {app_name}
After=network.target

[Service]
User=ec2-user
WorkingDirectory=/home/ec2-user
ExecStart=/usr/bin/python3 /home/ec2-user/app_{app_name}.py
Restart=always

[Install]
WantedBy=multi-user.target
"""

with open(service_file, "w") as f:
    f.write(service_content)

os.system("sudo systemctl daemon-reload")
os.system(f"sudo systemctl enable flask-app-{app_name}")
os.system(f"sudo systemctl start flask-app-{app_name}")
print(f"Flask service for {app_name} has been set up and started")