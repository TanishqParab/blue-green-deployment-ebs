import zipfile
import sys
import os

def zip_app(app_dir, output_zip):
    with zipfile.ZipFile(output_zip, 'w') as zipf:
        for filename in ['app.py', 'requirements.txt']:
            file_path = os.path.join(app_dir, filename)
            if os.path.exists(file_path):
                zipf.write(file_path, arcname=filename)
            else:
                print(f"⚠️ Warning: {file_path} not found.")
                sys.exit(1)

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python zip_app.py <source_dir> <output_zip>")
        sys.exit(1)

    app_source_dir = sys.argv[1]
    app_zip_path = sys.argv[2]
    zip_app(app_source_dir, app_zip_path)