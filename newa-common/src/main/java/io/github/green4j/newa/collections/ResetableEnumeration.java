package io.github.green4j.newa.collections;

import java.util.Enumeration;

public interface ResetableEnumeration<T> extends Enumeration<T> {

    void reset();

}