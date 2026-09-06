package com.example.los.testsupport;

import java.util.List;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Captures log output so a test can assert on what did — and did not — reach the
 * logging pipeline.
 *
 * <p>This exists because "we are careful not to log personal data" is a claim,
 * and a claim that nothing enforces stops being true the first time somebody adds
 * a helpful debug statement. {@link SensitiveMarkers} defines synthetic values
 * that must never appear in a log line; the tests drive real operations with
 * those values and assert their absence.
 *
 * <p>The captured event includes the formatted message <em>and</em> the rendered
 * stack trace of any attached throwable, because an exception message is one of
 * the commonest ways a rejected value escapes into a log.
 */
public final class CapturedLogs implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;
    private final Level originalLevel;

    private CapturedLogs(Logger logger, ListAppender<ILoggingEvent> appender, Level originalLevel) {
        this.logger = logger;
        this.appender = appender;
        this.originalLevel = originalLevel;
    }

    /**
     * Captures everything logged under the given logger name.
     *
     * <p>Captures at TRACE deliberately. A test that only captured INFO would pass
     * while a DEBUG statement was writing an applicant's email address to the log
     * of any environment running at DEBUG.
     */
    public static CapturedLogs of(String loggerName) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        Level originalLevel = logger.getLevel();

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);

        return new CapturedLogs(logger, appender, originalLevel);
    }

    /** Captures everything logged anywhere in the platform. */
    public static CapturedLogs ofPlatform() {
        return of("com.example.los");
    }

    /** Every captured line, including rendered stack traces. */
    public List<String> lines() {
        return appender.list.stream().map(CapturedLogs::render).toList();
    }

    /** Everything captured, joined, for a single containment assertion. */
    public String all() {
        return lines().stream().collect(Collectors.joining("\n"));
    }

    private static String render(ILoggingEvent event) {
        StringBuilder rendered = new StringBuilder(event.getFormattedMessage());
        var throwable = event.getThrowableProxy();
        while (throwable != null) {
            rendered.append('\n').append(throwable.getClassName()).append(": ").append(throwable.getMessage());
            for (var frame : throwable.getStackTraceElementProxyArray()) {
                rendered.append('\n').append(frame.getSTEAsString());
            }
            throwable = throwable.getCause();
        }
        return rendered.toString();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        appender.stop();
    }
}
