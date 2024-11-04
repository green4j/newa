## Netty-based Web API (NeWA)

### REST Server
```
public class HelloRestServer extends DefaultRestServerStarter {

    public HelloRestServer() {
        super("HelloAPI",
                1,
                "My HelloAPI Server",
                "0.0.1",
                "127.0.0.1",
                9009);
    }

    @Override
    protected RestApi buildRestApi(final RestApi.Builder builder) {
        builder.getJson("/hello/{name}", (request, pathParameters, output) -> {
            output.stringValue(String.format("Hello %s!", pathParameters.parameterValue("name")));
        });
        builder.getJson("/jvm/info", new Json_JvmInfo());
        builder.getJson("/jvm/threads", new Json_JvmThreadDump());
        builder.getJson("/shutdown", new Json_Shutdown(aSwitch()));
        return builder.buildWithHelp(Json_Help.factory());
    }

    public static void main(String[] args) {
        new HelloRestServer().start();
    }
}

```

### Websocket Server

TBD
