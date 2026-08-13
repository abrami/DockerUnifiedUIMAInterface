package org.texttechnologylab.DockerUnifiedUIMAInterface.pipeline_storage;

import com.arangodb.entity.BaseDocument;
import de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData;
import org.apache.uima.jcas.JCas;

import java.util.HashMap;
import java.util.Map;
import java.util.Vector;


public class DUUIPipelineDocumentPerformance {
    private final Vector<DUUIPipelinePerformancePoint> _points;

    /**
     * Component logs collected for this document during processing (piggybacked on the
     * {@code /v1/process} response, see {@code DUUIComponentLog}). Accumulated across all
     * components of a document, then flushed once per document by the storage backend in
     * {@link #getLogs()} — the "batch per document" logging path.
     */
    private final Vector<DocumentLog> _logs;
    private final String _runKey;
    private Long _durationTotalSerialize;
    private Long _durationTotalDeserialize;
    private Long _durationTotalAnnotator;
    private Long _durationTotalMutexWait;
    private Long _durationTotal;
    private final Integer _documentSize;
    private final Long _documentWaitTime;
    private String document;

    /**
     * Stores the types of annotations and how many were made.
     */
    private final Map<String, Integer> annotationTypesCount;

    /**
     * Whether to track error documents in the database or not
     */
    private final boolean trackErrorDocs;

    public DUUIPipelineDocumentPerformance(String runKey, long waitDocumentTime, JCas jc, boolean trackErrorDocs) {
        this.trackErrorDocs = trackErrorDocs;

        _points = new Vector<>();
        _logs = new Vector<>();
        _runKey = runKey;

        _documentWaitTime = waitDocumentTime;
        _durationTotalDeserialize = 0L;
        _durationTotalSerialize = 0L;
        _durationTotalAnnotator = 0L;
        _durationTotalMutexWait = 0L;
        _durationTotal = 0L;
        if (jc.getDocumentText() != null) {
            _documentSize = jc.getDocumentText().length();
        } else {
            _documentSize = -1;
        }

        try {
            DocumentMetaData meta = DocumentMetaData.get(jc);
            document = meta.getDocumentUri();
            if (document == null) {
                document = meta.getDocumentId();
            }
            if (document == null) {
                document = meta.getDocumentTitle();
            }
        } catch (Exception e) {
            document = null;
        }
        annotationTypesCount = new HashMap<>();
    }

    /**
     * Whether to track error documents in the database or not
     *
     * @return true if error documents should be tracked, false otherwise
     */
    public boolean shouldTrackErrorDocs() {
        return trackErrorDocs;
    }

    public String getRunKey() {
        return _runKey;
    }

    public Vector<DUUIPipelinePerformancePoint> getPerformancePoints() {
        return _points;
    }

    /**
     * Record one component log line for this document. Called from {@code DUUIComponentLog.emit}
     * while parsing the {@code DUUI-Logs} response header, so logs ride along with the metrics
     * and get flushed together, once per document.
     *
     * @param level        the log level name (e.g. {@code INFO}, {@code WARN}, {@code ERROR})
     * @param logger       the originating logger name, may be {@code null}
     * @param message      the log message
     * @param stacktrace   an attached stacktrace, may be {@code null}
     * @param timestamp    epoch millis for the record
     * @param componentKey the component that produced the log, may be {@code null}
     */
    public void addLog(String level, String logger, String message, String stacktrace, long timestamp, String componentKey) {
        _logs.add(new DocumentLog(level, logger, message, stacktrace, timestamp, componentKey));
    }

    /**
     * The component logs collected for this document, in order. Written to the storage backend
     * alongside the performance points in {@code addMetricsForDocument}.
     */
    public Vector<DocumentLog> getLogs() {
        return _logs;
    }

    /**
     * A single component log line collected for a document. The raw fields are kept here (rather
     * than the console-formatted string) so storage backends can persist them into columns.
     */
    public static final class DocumentLog {
        private final String level;
        private final String logger;
        private final String message;
        private final String stacktrace;
        private final long timestamp;
        private final String componentKey;

        public DocumentLog(String level, String logger, String message, String stacktrace, long timestamp, String componentKey) {
            this.level = level;
            this.logger = logger;
            this.message = message;
            this.stacktrace = stacktrace;
            this.timestamp = timestamp;
            this.componentKey = componentKey;
        }

        public String getLevel() {
            return level;
        }

        public String getLogger() {
            return logger;
        }

        public String getMessage() {
            return message;
        }

        public String getStacktrace() {
            return stacktrace;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getComponentKey() {
            return componentKey;
        }
    }

    public void addData(long durationSerialize, long durationDeserialize, long durationAnnotator, long durationMutexWait, long durationComponentTotal, String componentKey, long serializeSize, JCas jc, String error) {
        _durationTotalDeserialize += durationDeserialize;
        _durationTotalSerialize += durationSerialize;
        _durationTotalAnnotator += durationAnnotator;
        _durationTotalMutexWait += durationMutexWait;
        _durationTotal += durationComponentTotal;

//        for (Annotation annotation : jc.getAnnotationIndex()) {
//            annotationTypesCount.put(
//                    annotation.getClass().getCanonicalName(),
//                    JCasUtil.select(jc, annotation.getClass()).size()
//            );
//        }

        _points.add(new DUUIPipelinePerformancePoint(durationSerialize, durationDeserialize, durationAnnotator, durationMutexWait, durationComponentTotal, componentKey, serializeSize, jc, error, document));
    }

    public long getDocumentWaitTime() {
        return _documentWaitTime;
    }

    public long getTotalTime() {
        return _durationTotal + _documentWaitTime;
    }

    public long getDocumentSize() {
        return _documentSize;
    }

    public Vector<BaseDocument> generateComponentPerformance(String docKey) {
        Vector<BaseDocument> docs = new Vector<>();
        for (DUUIPipelinePerformancePoint point : _points) {
            Map<String, Object> props = new HashMap<>();
            props.put("run", _runKey);
            props.put("compkey", point.getKey());
            props.put("performance", point.getProperties());
            props.put("docsize", _documentSize);
            BaseDocument doc = new BaseDocument();
            doc.setProperties(props);

            docs.add(doc);
        }
        return docs;
    }

    public BaseDocument toArangoDocument() {
        BaseDocument doc = new BaseDocument();
        Map<String, Object> props = new HashMap<>();

        props.put("pipelineKey", _runKey);
        props.put("total", _durationTotal);
        props.put("mutexsync", _durationTotalMutexWait);
        props.put("annotator", _durationTotalAnnotator);
        props.put("serialize", _durationTotalSerialize);
        props.put("deserialize", _durationTotalDeserialize);
        props.put("docsize", _documentSize);
        doc.setProperties(props);
        return doc;
    }

    public String getDocument() {
        return document;
    }

    public Map<String, Integer> getAnnotationTypesCount() {
        return annotationTypesCount;
    }
}
