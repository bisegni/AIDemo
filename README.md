# AI for ELOG: RAG Demo

## Setup
1. Install JRE 8 and JDK 21. `java --version` should yield something like:
```
java 21.0.5 2024-10-15 LTS
Java(TM) SE Runtime Environment (build 21.0.5+9-LTS-239)
Java HotSpot(TM) 64-Bit Server VM (build 21.0.5+9-LTS-239, mixed mode, sharing)
```
(You may also be able to use a Docker container such as https://adoptium.net/temurin/releases/ or https://hub.docker.com/_/eclipse-temurin but I haven't tried this yet.)

2. Spin up the Ollama server:
```
docker run -d -v ollama:/root/.ollama -p 11434:11434 --name ollama ollama/ollama
``` 
3. **From the terminal inside the Ollama Docker container,** pull the embedding and chat models:
```
ollama pull mxbai-embed-large
ollama pull llama3.1:latest
```

3. Spin up the pgvector database:
```
docker run -it --rm --name postgres -p 5432:5432 -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg17
```

4. Start the Spring server:
```
./gradlew bootRun
```

## API Demo

1. Create vector embeddings for the documents in `src/main/resources/data` and store them in the vector store:
```
curl "http://localhost:8080/api/docs/load"
```
(You can add more data from `src/main/resources/all_data/days`.)
This command takes several minutes unless you have a GPU. You should see activity in the logs of the `ollama` container.

2. Suggest a title and tags for an ELOG post:
```
curl "http://localhost:8080/title-tags?message=It%20was%20down%20due%20to%20a%20broken%20fan%20on%20the%20CPU.%20%5Cn%20To%20resolve%20the%20issue%20and%20revive%20the%20Alpha,%20a%20replacement%20part%20has%20to%20be%20ordered%20and%20arrive%20at%20SLAC%20first.%20Next,%20only%20K.%20Brobeck%20knows%20how%20to%20replace%20it,%20but%20he%20is%20currently%20away%20on%20vacation%20and%20back%20to%20work%20next%20MOnday.%20CTL%20will%20come%20up%20with%20a%20plan%20to%20fix%20the%20issue%20either%20by%20Brobeck%20direct%20someone%20over%20the%20phone%20to%20replace%20it%20or%20find%20someone%20else%20possibly%20knows%20how%20to%20replace%20it.%20Not%20likely%20to%20be%20fixed%20today%20or%20tomorrow,%20LM%20and%20LW%20are%20likely%20delayed.%20More%20updates%20needed."
```

3. Answer a question about the ELOG:
```
curl "http://localhost:8080/question-prompt?message=What%20happened%20with%20CATER%20on%20Jan%203,%202022?"
```
