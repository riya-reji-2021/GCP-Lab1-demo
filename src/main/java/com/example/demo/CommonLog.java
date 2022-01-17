package com.example.demo;

//import com.google.common.annotations.VisibleForTesting;
import org.apache.beam.sdk.schemas.JavaFieldSchema;
import org.apache.beam.sdk.schemas.annotations.DefaultSchema;

//@VisibleForTesting
/**
 * A class used for parsing JSON web server events
 */

@DefaultSchema(JavaFieldSchema.class)
public class CommonLog {
    @javax.annotation.Nullable int id;
    @javax.annotation.Nullable String name;
    @javax.annotation.Nullable String surname;
}

