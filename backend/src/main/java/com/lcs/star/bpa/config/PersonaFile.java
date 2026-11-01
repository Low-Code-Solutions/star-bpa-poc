package com.lcs.star.bpa.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.Resource;

/** The contents of {@code personas.yaml} (S22, L9). */
public record PersonaFile(List<Persona> users) {

    public record Persona(String username, String name, String employeeRef, String email, List<String> groups, List<Scope> scope) {

        public Persona {
            groups = groups == null ? List.of() : List.copyOf(groups);
            scope = scope == null ? List.of() : List.copyOf(scope);
        }
    }

    /** One {@code user_role_scope} row: a department or a project the user's group is limited to (docs/data-model.md 5.11). */
    public record Scope(String group, String departmentRef, String projectRef) {
    }

    public static PersonaFile load(Resource resource) {
        try {
            var environment = new StandardEnvironment();
            new YamlPropertySourceLoader().load("personas", resource).forEach(environment.getPropertySources()::addFirst);
            return new Binder(ConfigurationPropertySources.get(environment)).bindOrCreate("", PersonaFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }
}
