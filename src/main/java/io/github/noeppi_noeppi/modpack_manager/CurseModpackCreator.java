package io.github.noeppi_noeppi.modpack_manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.moandjiezana.toml.Toml;
import com.therandomlabs.curseapi.CurseAPI;
import com.therandomlabs.curseapi.CurseException;
import com.therandomlabs.curseapi.file.CurseDependency;
import com.therandomlabs.curseapi.file.CurseDependencyType;
import com.therandomlabs.curseapi.file.CurseFile;
import com.therandomlabs.curseapi.minecraft.MCVersion;
import com.therandomlabs.curseapi.minecraft.MCVersions;
import com.therandomlabs.curseapi.minecraft.modpack.CurseModpack;
import com.therandomlabs.curseapi.project.CurseProject;
import de.melanx.modlistcreator.ModListCreator;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class CurseModpackCreator {

    public static final Gson GSON = new GsonBuilder()
            .setLenient()
            .setPrettyPrinting()
            .create();

    public static void main(String[] args) throws CurseException, IOException {
        Path basePath = Paths.get(args.length > 0 ? args[0] : "");
        if (!Files.exists(basePath)) {
            Files.createDirectories(basePath);
        }

        Path tomlPath = basePath.resolve("pack.toml");
        if (!Files.isRegularFile(tomlPath)) {
            throw new IllegalArgumentException("pack.toml not found");
        }

        Toml toml = new Toml().read(Files.newBufferedReader(tomlPath));

        CurseModpack pack = CurseModpack.createEmpty();
        pack.name(toml.getString("name"));
        MCVersion mcv = MCVersions.get(toml.getString("minecraft"));
        pack.mcVersion(mcv);
        pack.forgeVersion(toml.getString("forge"));
        pack.author(toml.getString("author"));
        pack.version(toml.getString("version"));

        List<CurseFile> files = new ArrayList<>();
        for (Toml file : toml.getTables("file")) {
            String projectStr = file.getString("project");
            Optional<CurseProject> projectOption;
            try {
                projectOption = CurseAPI.project(Integer.parseInt(projectStr));
            } catch (NumberFormatException e) {
                projectOption = CurseAPI.project("/minecraft/mc-mods/" + projectStr);
            }
            if (projectOption.isEmpty()) {
                throw new NoSuchElementException("Project " + projectStr + " not found.");
            }

            CurseProject project = projectOption.get();

            String fileStr = file.getString("file");
            int fileId = -1;
            try {
                fileId = Integer.parseInt(fileStr);
            } catch (NumberFormatException e) {
                //
            }
            boolean found = false;
            for (CurseFile projectFile : project.files()) {
                if ((fileId >= 0 && projectFile.id() == fileId) || fileStr.equalsIgnoreCase(projectFile.nameOnDisk())) {
                    if (projectFile.gameVersionStrings().stream().noneMatch(gv -> gv.equalsIgnoreCase(mcv.versionString()))) {
                        throw new IllegalStateException("File " + projectFile.nameOnDisk() + " of project " + project.name() + " is not available for minecraft version " + mcv.versionString() + ".");
                    }
                    if (projectFile.gameVersionStrings().stream().anyMatch(gv -> gv.equalsIgnoreCase(MCVersions.FABRIC.versionString()))) {
                        throw new IllegalStateException("File " + projectFile.nameOnDisk() + " of project " + project.name() + " is a fabric mod.");
                    }
                    if (projectFile.gameVersionStrings().stream().anyMatch(gv -> gv.equalsIgnoreCase(MCVersions.RIFT.versionString()))) {
                        throw new IllegalStateException("File " + projectFile.nameOnDisk() + " of project " + project.name() + " is a rift mod.");
                    }
                    files.add(projectFile);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new NoSuchElementException("File " + fileStr + " of project " + project.name() + " not found.");
            }
        }

        List<CurseDependency> dependencies = new ArrayList<>();
        for (CurseFile file : files) {
            dependencies.addAll(file.dependencies(CurseDependencyType.REQUIRED));
        }
        Set<Integer> additionalProjects = dependencies.stream()
                .map(CurseDependency::projectID)
                .filter(projectId -> files.stream().noneMatch(file -> file.id() == projectId))
                .collect(Collectors.toSet());
        for (int projectId : additionalProjects) {
            //noinspection OptionalGetWithoutIsPresent
            CurseProject project = CurseAPI.project(projectId).get();
            Optional<CurseFile> projectFile = new ArrayList<>(project.files()).stream()
                    .filter(file -> file.gameVersionStrings().stream().anyMatch(gv -> gv.equalsIgnoreCase(mcv.versionString()))
                            && file.gameVersionStrings().stream().noneMatch(gv -> gv.equalsIgnoreCase(MCVersions.FABRIC.versionString()))
                            && file.gameVersionStrings().stream().noneMatch(gv -> gv.equalsIgnoreCase(MCVersions.RIFT.versionString()))).max((f1, f2) -> f2.id() == f1.id() ? 0 : (f1.olderThan(f2) ? -1 : 1));
            if (projectFile.isEmpty()) {
                throw new IllegalStateException("Could not resolve dependency: " + projectId);
            }
            files.add(projectFile.get());
        }

        pack.files(files);

        JsonObject json = GSON.fromJson(pack.toJSON(), JsonObject.class);
        JsonObject loaders = json.get("minecraft").getAsJsonObject().get("modLoaders").getAsJsonArray().get(0).getAsJsonObject();
        loaders.addProperty("id", "forge-" + toml.getString("forge"));
        if (Files.isDirectory(basePath.resolve("overrides"))) {
            json.addProperty("overrides", "overrides");
        }

        ModListCreator.writeModList(pack, Files.newBufferedWriter(basePath.resolve("modlist.html"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));

        Writer manifest = Files.newBufferedWriter(basePath.resolve("manifest.json"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        manifest.write(GSON.toJson(json));
        manifest.write("\n");
        manifest.close();

        Path buildPath = basePath.resolve("build");
        if (!Files.exists(buildPath)) {
            Files.createDirectories(buildPath);
        }

        OutputStream packOut = Files.newOutputStream(buildPath.resolve((pack.name() + "-" + pack.version() + ".zip").replace(' ', '_')), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        ZipOutputStream zout = new ZipOutputStream(packOut);

        zout.putNextEntry(new ZipEntry("manifest.json"));
        Files.copy(basePath.resolve("manifest.json"), zout);
        zout.closeEntry();

        zout.putNextEntry(new ZipEntry("modlist.html"));
        Files.copy(basePath.resolve("modlist.html"), zout);
        zout.closeEntry();

        Path overridesPath = basePath.resolve("overrides").toAbsolutePath().normalize();
        if (Files.isDirectory(overridesPath)) {
            Files.walk(overridesPath).forEach(path -> {
                try {
                    String entry = overridesPath.relativize(path.normalize()).toString();
                    if (Files.isDirectory(path) && !entry.endsWith("/")) {
                        entry = entry + "/";
                    } else if (!Files.isDirectory(path) && entry.endsWith("/")) {
                        entry = entry.substring(0, entry.length() - 1);
                    }
                    if (!entry.startsWith("/")) {
                        entry = "/" + entry;
                    }
                    entry = "overrides" + entry;
                    zout.putNextEntry(new ZipEntry(entry));
                    if (Files.isRegularFile(path)) {
                        Files.copy(path, zout);
                    }
                    zout.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        zout.close();
        packOut.close();


        System.out.println("DONE");
        System.exit(0);
    }
}