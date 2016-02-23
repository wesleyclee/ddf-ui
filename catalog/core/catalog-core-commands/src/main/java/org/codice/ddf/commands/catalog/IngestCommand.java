/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.commands.catalog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.commons.lang.StringUtils;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.codice.ddf.commands.catalog.facade.CatalogFacade;
import org.codice.ddf.platform.util.Exceptions;
import org.fusesource.jansi.Ansi;
import org.joda.time.Period;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ddf.catalog.Constants;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.operation.CreateRequest;
import ddf.catalog.operation.CreateResponse;
import ddf.catalog.operation.impl.CreateRequestImpl;
import ddf.catalog.source.IngestException;
import ddf.catalog.source.SourceUnavailableException;
import ddf.catalog.transform.CatalogTransformerException;
import ddf.catalog.transform.InputTransformer;

/**
 * Custom Karaf command for ingesting records into the Catalog.
 */
@Command(scope = CatalogCommands.NAMESPACE, name = "ingest", description = "Ingests Metacards into the Catalog.")
public class IngestCommand extends CatalogCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestCommand.class);

    private static final Logger INGEST_LOGGER =
            LoggerFactory.getLogger(Constants.INGEST_LOGGER_NAME);

    private static final int DEFAULT_BATCH_SIZE = 500;

    private final PeriodFormatter timeFormatter = new PeriodFormatterBuilder().printZeroRarelyLast()
            .appendDays()
            .appendSuffix(" day", " days")
            .appendSeparator(" ")
            .appendHours()
            .appendSuffix(" hour", " hours")
            .appendSeparator(" ")
            .appendMinutes()
            .appendSuffix(" minute", " minutes")
            .appendSeparator(" ")
            .appendSeconds()
            .appendSuffix(" second", " seconds")
            .toFormatter();

    private final AtomicInteger ingestCount = new AtomicInteger();

    private final AtomicInteger ignoreCount = new AtomicInteger();

    private final AtomicBoolean doneBuildingQueue = new AtomicBoolean();

    private final AtomicInteger processingThreads = new AtomicInteger();

    private final AtomicInteger fileCount = new AtomicInteger(Integer.MAX_VALUE);

    @Argument(name = "File path or Directory path", description =
            "File path to a record or a directory of files to be ingested. Paths are absolute and must be in quotes."
                    + " This command can only detect roughly 2 billion records in one folder. Individual operating system limits might also apply.", index = 0, multiValued = false, required = true)
    String filePath = null;

    // DDF-535: Remove this argument in ddf-3.0
    @Argument(name = "Batch size", description = "Number of Metacards to ingest at a time. Change this argument based on system memory and catalog provider limits. [DEPRECATED: use --batchsize option instead]", index = 1, multiValued = false, required = false)
    int deprecatedBatchSize = DEFAULT_BATCH_SIZE;

    // DDF-535: remove "Transformer" alias in ddf-3.0
    @Option(name = "--transformer", required = false, aliases = {"-t",
            "Transformer"}, multiValued = false, description = "The metacard transformer ID to use to transform data files into metacards. The default metacard transformer is the Java serialization transformer.")
    String transformerId = DEFAULT_TRANSFORMER_ID;

    // DDF-535: Remove "Multithreaded" alias in ddf-3.0
    @Option(name = "--multithreaded", required = false, aliases = {"-m",
            "Multithreaded"}, multiValued = false, description = "Number of threads to use when ingesting. Setting this value too high for your system can cause performance degradation.")
    int multithreaded = 8;

    // DDF-535: remove "-d" and "Ingest Failure Directory" aliases in ddf-3.0
    @Option(name = "--failedDir", required = false, aliases = {"-d", "-f",
            "Ingest Failure Directory"}, multiValued = false, description = "The directory to put files that failed to ingest.  Using this option will force a batch size of 1.")
    String failedDir = null;

    @Option(name = "--batchsize", required = false, aliases = {
            "-b"}, multiValued = false, description = "Number of Metacards to ingest at a time. Change this argument based on system memory and catalog provider limits.")
    int batchSize = DEFAULT_BATCH_SIZE;

    @Option(name = "--ignore", required = false, aliases = {
            "-i"}, multiValued = true, description = "File extension(s) or file name(s) to ignore during ingestion (-i '.txt' -i 'image.jpg' -i 'file' )")
    List<String> ignoreList;

    File failedIngestDirectory = null;

    InputTransformer transformer = null;

    @Override
    protected Object executeWithSubject() throws Exception {

        final CatalogFacade catalog = getCatalog();
        final File inputFile = new File(filePath);

        if (!inputFile.exists()) {
            printErrorMessage("File or directory [" + filePath + "] must exist.");
            console.println("If the file does indeed exist, try putting the path in quotes.");
            return null;
        }

        if (deprecatedBatchSize != DEFAULT_BATCH_SIZE) {
            // user specified the old style batch size, so use that
            printErrorMessage(
                    "Batch size positional argument is DEPRECATED, please use --batchsize option instead.");
            batchSize = deprecatedBatchSize;
        }

        if (batchSize <= 0) {
            printErrorMessage("A batch size of [" + batchSize
                    + "] was supplied. Batch size must be greater than 0.");
            return null;
        }

        if (!StringUtils.isEmpty(failedDir)) {
            failedIngestDirectory = new File(failedDir);
            if (!verifyFailedIngestDirectory()) {
                return null;
            }

            /**
             * Batch size is always set to 1, when using an Ingest Failure Directory.  If a batch size is specified by the user, issue 
             * a warning stating that a batch size of 1 will be used.
             */
            if (batchSize != DEFAULT_BATCH_SIZE) {
                console.println(
                        "WARNING: An ingest failure directory was supplied in addition to a batch size of "
                                + batchSize
                                + ". When using an ingest failure directory, the batch size must be 1. Setting batch size to 1.");
            }

            batchSize = 1;
        }

        BundleContext bundleContext = getBundleContext();
        if (!DEFAULT_TRANSFORMER_ID.equals(transformerId)) {
            ServiceReference[] refs = null;

            try {
                refs = bundleContext.getServiceReferences(InputTransformer.class.getName(),
                        "(|" + "(" + Constants.SERVICE_ID + "=" + transformerId + ")" + ")");
            } catch (InvalidSyntaxException e) {
                throw new IllegalArgumentException(
                        "Invalid transformer transformerId: " + transformerId, e);
            }

            if (refs == null || refs.length == 0) {
                throw new IllegalArgumentException("Transformer " + transformerId + " not found");
            } else {
                transformer = (InputTransformer) bundleContext.getService(refs[0]);
            }
        }

        ForkJoinPool forkJoinPool = new ForkJoinPool(multithreaded);

        Stream<Path> ingestStream = forkJoinPool.submit(() -> Files.walk(inputFile.toPath(),
                FileVisitOption.FOLLOW_LINKS))
                .get();

        forkJoinPool.shutdown();

        int totalFiles = (inputFile.isDirectory()) ? inputFile.list().length : 1;
        fileCount.getAndSet(totalFiles);

        final Queue<Metacard> metacardQueue = new ConcurrentLinkedQueue<>();

        BlockingQueue<Runnable> blockingQueue = new ArrayBlockingQueue<>(multithreaded);
        RejectedExecutionHandler rejectedExecutionHandler =
                new ThreadPoolExecutor.CallerRunsPolicy();
        ExecutorService executorService = new ThreadPoolExecutor(multithreaded,
                multithreaded,
                0L,
                TimeUnit.MILLISECONDS,
                blockingQueue,
                rejectedExecutionHandler);

        final long start = System.currentTimeMillis();

        printProgressAndFlush(start, fileCount.get(), 0);

        executorService.submit(new Runnable() {
            @Override
            public void run() {
                buildQueue(ingestStream, metacardQueue, start);
            }
        });

        final ScheduledExecutorService batchScheduler =
                Executors.newSingleThreadScheduledExecutor();
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                submitToCatalog(batchScheduler,
                        executorService,
                        metacardQueue,
                        catalog,
                        batchSize,
                        start);
            }
        });

        while (!doneBuildingQueue.get() || processingThreads.get() != 0) {
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                // ignore
            }
        }

        try {
            executorService.shutdown();
            batchScheduler.shutdown();
        } catch (SecurityException e) {
            LOGGER.error("Executor service shutdown was not permitted: {}", e);
        }

        printProgressAndFlush(start, fileCount.get(), ingestCount.get() + ignoreCount.get());
        long end = System.currentTimeMillis();
        console.println();
        String elapsedTime = timeFormatter.print(new Period(start, end).withMillis(0));

        console.println();
        console.printf(" %d file(s) ingested in %s %n", ingestCount.get(), elapsedTime);

        LOGGER.info("{} file(s) ingested in {} [{} records/sec]",
                ingestCount.get(),
                elapsedTime,
                calculateRecordsPerSecond(ingestCount.get(), start, end));
        INGEST_LOGGER.info("{} file(s) ingested in {} [{} records/sec]",
                ingestCount.get(),
                elapsedTime,
                calculateRecordsPerSecond(ingestCount.get(), start, end));

        if (fileCount.get() != ingestCount.get()) {
            console.println();
            if ((fileCount.get() - ingestCount.get() - ignoreCount.get()) >= 1) {
                String failedAmount = Integer.toString(
                        fileCount.get() - ingestCount.get() - ignoreCount.get());
                printErrorMessage(failedAmount
                        + " file(s) failed to be ingested.  See the ingest log for more details.");
                INGEST_LOGGER.warn("{} files(s) failed to be ingested.", failedAmount);
            }
            if (ignoreList != null) {
                String ignoredAmount = Integer.toString(ignoreCount.get());
                printColor(Ansi.Color.YELLOW,
                        ignoredAmount + " file(s) ignored.  See the ingest log for more details.");
                INGEST_LOGGER.warn("{} files(s) were ignored.", ignoredAmount);
            }
        }
        console.println();

        return null;
    }

    /**
     * Helper method to build ingest log strings
     */
    private String buildIngestLog(ArrayList<Metacard> metacards) {
        StringBuilder strBuilder = new StringBuilder();

        final String newLine = System.getProperty("line.separator");

        for (int i = 0; i < metacards.size(); i++) {
            Metacard card = metacards.get(i);
            strBuilder.append(newLine)
                    .append("Batch #: ")
                    .append(i + 1)
                    .append(" | ");
            if (card != null) {
                if (card.getTitle() != null) {
                    strBuilder.append("Metacard Title: ")
                            .append(card.getTitle())
                            .append(" | ");
                }
                if (card.getId() != null) {
                    strBuilder.append("Metacard ID: ")
                            .append(card.getId())
                            .append(" | ");
                }
            } else {
                strBuilder.append("Null Metacard");
            }
        }
        return strBuilder.toString();
    }

    private void logIngestException(IngestException exception, File inputFile) {
        LOGGER.debug("Failed to ingest file [{}].", inputFile.getAbsolutePath(), exception);
        INGEST_LOGGER.warn("Failed to ingest file [{}]:  \n{}",
                inputFile.getAbsolutePath(),
                Exceptions.getFullMessage(exception));
    }

    private CreateResponse createMetacards(CatalogFacade catalog, List<Metacard> listOfMetaCards)
            throws IngestException, SourceUnavailableException {
        CreateRequest createRequest = new CreateRequestImpl(listOfMetaCards);
        return catalog.create(createRequest);
    }

    private Metacard readMetacard(File file) throws IngestException {
        Metacard result = null;

        FileInputStream fis = null;
        ObjectInputStream ois = null;

        try {
            if (DEFAULT_TRANSFORMER_ID.matches(transformerId)) {
                ois = new ObjectInputStream(new FileInputStream(file));
                result = (Metacard) ois.readObject();
                ois.close();
            } else {
                fis = new FileInputStream(file);
                result = generateMetacard(fis);
                if (StringUtils.isBlank(result.getTitle())) {
                    LOGGER.debug("Metacard title was blank. Setting title to filename.");
                    result.setAttribute(new AttributeImpl(Metacard.TITLE, file.getName()));
                }
                fis.close();
            }
        } catch (IOException | IllegalArgumentException | ClassNotFoundException e) {
            throw new IngestException(e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e1) {
                    console.println(e1);
                }
            }

            if (ois != null) {
                try {
                    ois.close();
                } catch (IOException e2) {
                    console.println(e2);
                }
            }
        }

        return result;
    }

    private Metacard generateMetacard(InputStream message) throws IOException {
        try {
            if (message != null) {
                return transformer.transform(message);
            } else {
                throw new IllegalArgumentException("data file is null.");
            }

        } catch (CatalogTransformerException e) {
            throw new IllegalArgumentException(
                    "Transformation Failed for transformer: " + transformerId, e);
        }
    }

    private boolean verifyFailedIngestDirectory() {
        if (!failedIngestDirectory.exists()) {
            makeFailedIngestDirectory();
        }

        if (!failedIngestDirectory.canWrite()) {
            printErrorMessage(
                    "Directory [" + failedIngestDirectory.getAbsolutePath() + "] is not writable.");
            return false;
        } else {
            return true;
        }
    }

    private void makeFailedIngestDirectory() {
        if (!failedIngestDirectory.mkdirs()) {
            printErrorMessage(
                    "Unable to create directory [" + failedIngestDirectory.getAbsolutePath()
                            + "].");
        }
    }

    private boolean processBatch(CatalogFacade catalog, ArrayList<Metacard> metacards)
            throws SourceUnavailableException {
        CreateResponse createResponse = null;

        try {
            createResponse = createMetacards(catalog, metacards);
        } catch (IngestException e) {
            printErrorMessage("Error executing command: " + e.getMessage());
            if (INGEST_LOGGER.isWarnEnabled()) {
                INGEST_LOGGER.warn("Error ingesting metacard batch {}",
                        buildIngestLog(metacards),
                        e);
            }
        } catch (SourceUnavailableException e) {
            if (INGEST_LOGGER.isWarnEnabled()) {
                INGEST_LOGGER.warn("Error on process batch, local provider not available. {}"
                                + " metacards failed to ingest. {}",
                        metacards.size(),
                        buildIngestLog(metacards),
                        e);
            }
        } finally {
            processingThreads.decrementAndGet();
        }

        if (createResponse != null) {
            ingestCount.getAndAdd(createResponse.getCreatedMetacards()
                    .size());
        }
        return createResponse != null;
    }

    private void moveToFailedIngestDirectory(File source) {
        File destination = new File(
                failedIngestDirectory.getAbsolutePath() + File.separator + source.getName());

        if (!source.renameTo(destination)) {
            printErrorMessage("Unable to move source file [" + source.getAbsolutePath() + "] to ["
                    + failedIngestDirectory + "].");
        }
    }

    private void buildQueue(Stream<Path> ingestStream, Queue<Metacard> metacardQueue, long start) {
        ingestStream.filter(a -> !a.toFile()
                .isDirectory())
                .forEach(a -> {
                    File file = a.toFile();

                    if (file.isHidden()) {
                        ignoreCount.incrementAndGet();
                    } else {
                        String extension = file.getName();

                        if (extension.contains(".")) {
                            int x = extension.indexOf('.');
                            extension = extension.substring(x);
                        }

                        if (ignoreList != null && (ignoreList.contains(extension)
                                || ignoreList.contains(file.getName()))) {
                            ignoreCount.incrementAndGet();
                            printProgressAndFlush(start,
                                    fileCount.get(),
                                    ingestCount.get() + ignoreCount.get());
                        } else {
                            Metacard result;
                            try {
                                result = readMetacard(file);
                            } catch (IngestException e) {
                                result = null;
                                logIngestException(e, file);
                                if (failedIngestDirectory != null) {
                                    moveToFailedIngestDirectory(file);
                                }
                                printErrorMessage(
                                        "Failed to ingest file [" + file.getAbsolutePath() + "].");
                                if (INGEST_LOGGER.isWarnEnabled()) {
                                    INGEST_LOGGER.warn("Failed to ingest file [{}].",
                                            file.getAbsolutePath());
                                }
                            }

                            if (result != null) {
                                metacardQueue.add(result);
                            }
                        }
                    }
                });
        doneBuildingQueue.set(true);
    }

    private void submitToCatalog(ScheduledExecutorService batchScheduler,
            ExecutorService executorService, Queue<Metacard> metacardQueue, CatalogFacade catalog,
            int batchSize, long start) {

        batchScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                int queueSize = metacardQueue.size();

                if (queueSize > 0) {
                    ArrayList<Metacard> metacardBatch = new ArrayList<>();
                    if (queueSize > batchSize) {
                        for (int i = 0; i < batchSize; i++) {
                            metacardBatch.add(metacardQueue.remove());
                        }
                        processingThreads.incrementAndGet();
                    } else if (doneBuildingQueue.get()) {
                        for (Metacard metacard : metacardQueue) {
                            metacardBatch.add(metacardQueue.remove());
                        }
                        processingThreads.incrementAndGet();
                    }
                    if (metacardBatch.size() > 0) {
                        executorService.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    processBatch(catalog, metacardBatch);
                                } catch (SourceUnavailableException e) {

                                }
                            }
                        });
                        printProgressAndFlush(start,
                                fileCount.get(),
                                ingestCount.get() + ignoreCount.get());
                    }
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

}