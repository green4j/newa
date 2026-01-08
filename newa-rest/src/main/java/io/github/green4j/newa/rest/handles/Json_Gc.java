package io.github.green4j.newa.rest.handles;

public class Json_Gc extends Json_Execute {
    public Json_Gc() {
        super(System::gc);
    }
}