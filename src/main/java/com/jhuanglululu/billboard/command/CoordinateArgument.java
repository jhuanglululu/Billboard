package com.jhuanglululu.billboard.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import io.papermc.paper.command.brigadier.argument.resolvers.AngleResolver;
import java.util.List;

/**
 * The Brigadier argument behind each of {@code spawn}'s three coordinates: one whitespace-delimited
 * token, handed on verbatim for {@link Coordinate} to interpret.
 *
 * <p><b>Why not {@code StringArgumentType.word()}.</b> Brigadier's unquoted-string reader stops at
 * {@code ~}, so a {@code word} argument cannot even see the token vanilla users expect to type.
 * This reads to the next space instead, and leaves every judgement about the contents to
 * {@link Coordinate} — so a malformed coordinate is reported by the plugin, in the plugin's own
 * message style, naming the token, rather than as a raw Brigadier syntax error.
 *
 * <p><b>Why {@code minecraft:angle} is the advertised native type.</b> The client parses the
 * command tree locally to colour it and to decide when to ask the server for suggestions, so the
 * type it is told about has to accept everything this one does. {@code minecraft:angle} is the one
 * vanilla type that is a single token and understands the {@code ~} prefix; it never runs on our
 * side (this class parses), it only makes the client agree that {@code ~-2.5} is a whole, valid
 * argument.
 */
final class CoordinateArgument implements CustomArgumentType<String, AngleResolver> {

    /** Stateless, so one instance serves every coordinate of every registration. */
    static final CoordinateArgument INSTANCE = new CoordinateArgument();

    private CoordinateArgument() {}

    @Override
    public String parse(StringReader reader) {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public ArgumentType<AngleResolver> getNativeType() {
        return ArgumentTypes.angle();
    }

    @Override
    public List<String> getExamples() {
        return List.of("0", "10.5", "~", "~-3");
    }
}
