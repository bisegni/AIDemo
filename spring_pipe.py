from typing import List, Union, Generator, Iterator
import requests
import json

class Pipeline:
    def __init__(self):
        self.name = "ChatEED"
        pass

    async def on_startup(self):
        # This function is called when the server is started.
        print(f"on_startup:{__name__}")
        pass

    async def on_shutdown(self):
        # This function is called when the server is shutdown.
        print(f"on_shutdown:{__name__}")
        pass

    
    def pipe(self, user_message: str, model_id: str, messages: List[dict], body: dict) -> Union[str, Generator, Iterator]:
        # This function is called when a new user_message is receieved.
        SPRING_BASE_URL = "http://localhost:8081"
        MODEL = "llama3.1"

        try:
            r = requests.get(
                url=f"{SPRING_BASE_URL}/question-prompt",
                params={'message': user_message},
                stream=True,
            )

            r.raise_for_status()
            if body["stream"]:
                # return r.iter_lines()
                def my_iter():
                    for j in r.iter_lines():
                        yield str(json.loads(j)['response'])
                return my_iter()
            else:
                return r.json()
        except Exception as e:
            return f"Pipeline error: {e}"