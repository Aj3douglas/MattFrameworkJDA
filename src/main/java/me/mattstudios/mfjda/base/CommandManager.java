package me.mattstudios.mfjda.base;

import me.mattstudios.mfjda.annotations.Command;
import me.mattstudios.mfjda.annotations.Prefix;
import me.mattstudios.mfjda.base.components.MessageResolver;
import me.mattstudios.mfjda.base.components.ParameterResolver;
import me.mattstudios.mfjda.base.components.RequirementResolver;
import me.mattstudios.mfjda.exceptions.MfException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The class for registering commands, messages, requirements and parameters
 */
@SuppressWarnings("unused")
public final class CommandManager extends ListenerAdapter {

    private final JDA jda;
    private String globalPrefix;

    private final ParameterHandler parameterHandler;
    private final RequirementHandler requirementHandler = new RequirementHandler();
    private final MessageHandler messageHandler = new MessageHandler();

    private final Set<String> prefixes = new HashSet<>();
    private final Map<String, CommandHandler> commands = new HashMap<>();

    /**
     * Registers a new CommandManager for the given JDA instance
     * and using the given global prefix as the prefix for the
     * commands registered using this instance
     *
     * @param jda          the JDA instance for the bot
     * @param globalPrefix the prefix for all the commands registered
     *                     using this instance
     */
    public CommandManager(final JDA jda, final String globalPrefix) {
        this.jda = jda;
        this.parameterHandler = new ParameterHandler(jda);
        this.globalPrefix = globalPrefix;

        jda.addEventListener(this);
    }

    /**
     * Registers a new CommandManager for the given JDA instance
     *
     * @param jda the JDA instance for the bot
     */
    public CommandManager(final JDA jda) {
        this(jda, null);
    }

    /**
     * Registers a command
     *
     * @param command the command to register
     */
    public void register(final CommandBase command) {
        // Injects JDA into the command class
        command.setJda(jda);

        if (command instanceof BuildCommand) {
            final BuildCommand buildCommand = (BuildCommand) command;
            addCommands(buildCommand.getCommands(), buildCommand.getPrefixes(), buildCommand);
            return;
        }

        final Class<?> commandClass = command.getClass();

        // Checks for the Prefix annotation
        if (!commandClass.isAnnotationPresent(Prefix.class) && globalPrefix == null) {
            throw new MfException("Class " + commandClass.getName() + " needs to be annotated with @Prefix!");
        }

        // Checks for the Prefix annotation
        if (!commandClass.isAnnotationPresent(Command.class)) {
            throw new MfException("Class " + commandClass.getName() + " needs to be annotated with @Command!");
        }

        final List<String> prefixes = !commandClass.isAnnotationPresent(Prefix.class) ? new ArrayList<>() : Arrays.asList(commandClass.getAnnotation(Prefix.class).value());
        final List<String> commands = Arrays.asList(commandClass.getAnnotation(Command.class).value());

        if (prefixes.isEmpty()) prefixes.add(globalPrefix);

        // Adds a new command for each prefix added
        addCommands(commands, prefixes, command);

    }

    /**
     * Registers the given list of commands
     *
     * @param commands the list of commands to register
     */
    public void register(final List<CommandBase> commands) {
        commands.forEach(this::register);
    }

    /**
     * Method to add the registered commands
     *
     * @param commands The command and it's aliases
     * @param prefixes The prefix to add
     * @param command  The base command
     */
    private void addCommands(final List<String> commands, final List<String> prefixes, final CommandBase command) {
        for (final String commandName : commands) {
            final CommandHandler commandHandler = this.commands.get(commandName);
            if (commandHandler != null) {
                commandHandler.registerSubCommands(command);
                continue;
            }

            this.prefixes.addAll(prefixes);
            this.commands.put(commandName, new CommandHandler(parameterHandler, messageHandler, requirementHandler, command, prefixes));
        }
    }

    /**
     * Unregisters the given command and prefix
     *
     * @param prefix The prefix
     * @param command The command name
     */
    public void unregister(final String prefix, final String command) {
        if (!prefixes.contains(prefix)) return;
        commands.remove(command);

        final Optional<CommandHandler> optional = commands.values()
                .parallelStream()
                .filter(commandHandler -> commandHandler.isPrefix(prefix))
                .findAny();

        if (!optional.isPresent()) prefixes.remove(prefix);
    }

    /**
     * Registers the new requirement
     *
     * @param id                  The requirement ID
     * @param requirementResolver The requirement resolver
     */
    public void registerRequirement(final String id, final RequirementResolver requirementResolver) {
        requirementHandler.register(id, requirementResolver);
    }

    /**
     * Registers the new parameter
     *
     * @param clss              The parameter class
     * @param parameterResolver The requirement resolver
     */
    public void registerParameter(final Class<?> clss, final ParameterResolver parameterResolver) {
        parameterHandler.register(clss, parameterResolver);
    }

    /**
     * Registers the new message
     *
     * @param id              The message ID
     * @param messageResolver The requirement resolver
     */
    public void registerMessage(final String id, final MessageResolver messageResolver) {
        messageHandler.register(id, messageResolver);
    }

    @Override
    public void onGuildMessageReceived(final @NotNull GuildMessageReceivedEvent event) {
        final Message message = event.getMessage();
        final List<String> arguments = Arrays.asList(message.getContentRaw().split(" "));

        // Checks if the message starts with the prefixes
        if (arguments.isEmpty()) return;

        // Gets the prefix being used and checks if it's command or not
        final String prefix = getPrefix(arguments.get(0));
        if (prefix == null) return;

        // Checks if the command exists
        final String commandName = arguments.get(0).replace(prefix, "");
        final CommandHandler commandHandler = commands.get(commandName);
        if (commandHandler == null) {
            messageHandler.sendMessage("cmd.no.exists", message);
            return;
        }

        // Checks if the command given uses this prefix
        if (!commandHandler.isPrefix(prefix)) {
            messageHandler.sendMessage("cmd.no.exists", message);
            return;
        }

        commandHandler.executeCommand(message, arguments);
    }

    /**
     * Checks if the message starts with the given prefix
     *
     * @param command The message to check
     * @return Whether or not the message starts with the prefix from the list
     */
    private String getPrefix(final String command) {
        for (String prefix : prefixes) {
            final Pattern pattern = Pattern.compile("^" + Pattern.quote(prefix) + "[\\w]");
            final Matcher matcher = pattern.matcher(command);

            if (matcher.find()) {
                return prefix;
            }
        }

        return null;
    }
}
