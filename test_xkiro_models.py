from openai import OpenAI

API_KEY = "sk-xt-66ecb2f30a8c35bbfb9cae390a71dfcdcd5d4f96998de892"
BASE_URL = "https://api.xkiro.com/v1"

client = OpenAI(base_url=BASE_URL, api_key=API_KEY)

models = [
    "mistralai/mistral-large-2512",
    "openai/gpt-5.3-codex-spark",
    "minimax/minimax-m3:free"
]

print("Testing Xkiro models with user API Key...")
for model in models:
    try:
        res = client.chat.completions.create(
            model=model,
            messages=[{"role": "user", "content": "Hello JARVIS!"}]
        )
        print(f"[{model}] SUCCESS: {res.choices[0].message.content}")
    except Exception as e:
        print(f"[{model}] ERROR: {e}")
