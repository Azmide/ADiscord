package com.azmide.adiscord;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;

import java.util.List;

/**
 * Downloads the runtime libraries on first start instead of shading them into the jar.
 * Versions here must match the ones in pom.xml.
 */
@SuppressWarnings("UnstableApiUsage")
public class ADiscordLoader implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());

        // Voice support is not used, so skip the audio libraries
        resolver.addDependency(library("net.dv8tion:JDA:6.7.0",
                new Exclusion("club.minnced", "opus-java", "*", "*"),
                new Exclusion("com.google.crypto.tink", "tink", "*", "*")));
        resolver.addDependency(library("com.zaxxer:HikariCP:7.1.0"));
        resolver.addDependency(library("org.xerial:sqlite-jdbc:3.53.4.0"));
        resolver.addDependency(library("com.mysql:mysql-connector-j:26.7.0",
                new Exclusion("com.google.protobuf", "protobuf-java", "*", "*")));

        classpathBuilder.addLibrary(resolver);
    }

    private static Dependency library(String coordinates, Exclusion... exclusions) {
        return new Dependency(new DefaultArtifact(coordinates), null, false, List.of(exclusions));
    }
}
