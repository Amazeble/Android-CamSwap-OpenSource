import os
import requests
import sys

bot_token = os.environ.get('BOT_TOKEN')
chat_id = os.environ.get('CHAT_ID')
release_name = os.environ.get('RELEASE_NAME', 'Unknown')
release_tag = os.environ.get('RELEASE_TAG', 'Unknown')
release_body = os.environ.get('RELEASE_BODY', '')
release_url = os.environ.get('RELEASE_URL', '')
repo = os.environ.get('REPO', 'Unknown')

if not bot_token or not chat_id:
    print('Missing BOT_TOKEN or CHAT_ID')
    sys.exit(0)

message = "New Release - " + repo + "\n"
message += "Release: " + release_name + " (" + release_tag + ")\n\n"
message += release_body + "\n\n"
message += release_url

try:
    response = requests.post(
        f'https://api.telegram.org/bot{bot_token}/sendMessage',
        data={'chat_id': chat_id, 'text': message, 'parse_mode': 'HTML'},
        timeout=10
    )
    response.raise_for_status()
    print('Telegram notification sent successfully')
except Exception as e:
    print(f'Failed to send Telegram notification: {e}')
    sys.exit(1)
