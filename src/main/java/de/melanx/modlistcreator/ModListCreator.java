package de.melanx.modlistcreator;

import com.therandomlabs.curseapi.CurseException;
import com.therandomlabs.curseapi.minecraft.modpack.CurseModpack;
import com.therandomlabs.curseapi.project.CurseMember;
import com.therandomlabs.curseapi.project.CurseProject;

import javax.annotation.WillClose;
import java.io.IOException;
import java.io.Writer;

public class ModListCreator {

    public static void writeModList(CurseModpack pack, @WillClose Writer writer) throws IOException, CurseException {
		writer.write("<ul>\n");
		pack.files().stream().map(file -> {
            try {
                return String.format("<li>%s (by %s)</li>\n", getFormattedProject(file.project()), getFormattedAuthor(file.project().author()));
            } catch (CurseException e) {
                throw new RuntimeException(e);
            }
        }).sorted().forEach(str -> {
            try {
                writer.write(str);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
		writer.write("</ul>\n");
		writer.close();
    }

    private static String getFormattedProject(CurseProject project) {
    	return String.format("<a href=\"https://www.curseforge.com/minecraft/mc-mods/%s\">%s</a>", project.slug(), project.name());
	}

	private static String getFormattedAuthor(CurseMember member) {
		return String.format("<a href=\"https://www.curseforge.com/members/%s/projects\">%s</a>", member.name().toLowerCase(), member.name());
	}
}
