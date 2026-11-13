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

    public record Persona(String username, String name, String employeeRef, String email, List<String> groups, List<String> projects) {

        public Persona {
            groups = groups == null ? List.of() : List.copyOf(groups);
            projects = projects == null ? List.of() : List.copyOf(projects); // BPA-48: project_ref values for bpa.user_project
        }
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
