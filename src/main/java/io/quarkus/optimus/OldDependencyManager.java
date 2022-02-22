package io.quarkus.optimus;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class OldDependencyManager {

    final Map<String, String> ee8;
    final Map<String, Version> jakarta9;

    public static OldDependencyManager createDefault() {
        Properties ee8 = new Properties();
        Properties ee9 = new Properties();
        try {
            ee8.load(OldDependencyManager.class.getClassLoader().getResourceAsStream("jakarta8.properties"));
            ee9.load(OldDependencyManager.class.getClassLoader().getResourceAsStream("jakarta9.properties"));
            return new OldDependencyManager(ee8, ee9);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public OldDependencyManager(Properties ee8, Properties ee9) {
        this.ee8 = new HashMap<>();
        this.jakarta9 = new HashMap<>();
        GenericVersionScheme genericVersionScheme = new GenericVersionScheme();
        for (Map.Entry<Object, Object> entry : ee8.entrySet()) {
            this.ee8.put(entry.getKey().toString(), entry.getValue().toString());
        }
        for (Map.Entry<Object, Object> entry : ee9.entrySet()) {
            try {
                jakarta9.put(entry.getKey().toString(), genericVersionScheme.parseVersion(entry.getValue().toString()));
            } catch (InvalidVersionSpecificationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public boolean isOldApi(String group, String artifact, String version) {
        String key = group + ":" + artifact;
        if (ee8.containsKey(key)) {
            return true;
        }
        if (version == null) {
            return false;
        }
        Version nineVer = jakarta9.get(key);
        if (nineVer == null) {
            return false;
        }
        try {
            return nineVer.compareTo(new GenericVersionScheme().parseVersion(version)) > 0;
        } catch (InvalidVersionSpecificationException e) {
            throw new RuntimeException(e);
        }
    }

    public Artifact getReplacement(String group, String artifact) {
        String key = group + ":" + artifact;
        if (ee8.containsKey(key)) {
            key = ee8.get(key);
        }
        Version nineVer = jakarta9.get(key);
        if (nineVer == null) {
            throw new IllegalArgumentException();
        }
        String[] parts = key.split(":");
        return new DefaultArtifact(parts[0], parts[1], "jar", nineVer.toString());
    }
}
