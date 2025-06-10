package org.jkiss.dbeaver.tools.transfer.stream.exporter;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.tools.transfer.stream.IDocumentDataExporter;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporterSite;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class DataExporterGeoJSON extends StreamExporterAbstract implements IDocumentDataExporter {

    private DBDAttributeBinding[] columns;
    private int rowCount = 0;

    @Override
    public void init(IStreamDataExporterSite site) throws DBException {
        super.init(site);
    }

    @Override
    public void dispose() {
        super.dispose();
    }

    @Override
    public void exportHeader(DBCSession session) throws DBException, IOException {
        columns = getSite().getAttributes();
        PrintWriter out = getWriter();
        out.write("{\n");
        out.write("\"type\": \"FeatureCollection\",\n");
        out.write("\"features\": [\n");
        rowCount = 0;
    }

    @Override
    public void exportRow(DBCSession session, DBCResultSet resultSet, Object[] row) throws DBException, IOException {
        PrintWriter out = getWriter();

        if (rowCount++ > 0) {
            out.write(",\n");
        }

        out.write("{ \"type\": \"Feature\",\n");

        // Geometry
        out.write("\"geometry\": ");
        boolean geometryWritten = false;
        for (int i = 0; i < columns.length; i++) {
            if ("geometry".equalsIgnoreCase(columns[i].getLabel())) {
                writeGeometry(session, row[i]);
                geometryWritten = true;
                break;
            }
        }
        if (!geometryWritten) {
            out.write("null");
        }

        // Properties
        out.write(",\n\"properties\": ");
        for (int i = 0; i < columns.length; i++) {
            if ("properties".equalsIgnoreCase(columns[i].getLabel())) {
                out.write(row[i] != null ? row[i].toString() : "null");
                break;
            }
        }

        out.write("\n}");
    }

    @Override
    public void exportFooter(DBRProgressMonitor monitor) throws IOException {
        PrintWriter out = getWriter();
        out.write("\n]\n}");
    }

    private void writeGeometry(DBCSession session, Object value) throws IOException, DBException {
        PrintWriter out = getWriter();
        try {
            if (value instanceof byte[] bytes) {
                String base64 = Base64.getEncoder().encodeToString(bytes);
                out.write("\"WKB_BINARY_BASE64:" + escape(base64) + "\"");
            } else if (value instanceof String str) {
                out.write("\"WKB_HEX:" + escape(str.trim()) + "\"");
            } else if (value instanceof DBDContent content) {
                try (InputStream in = content.getContents(session.getProgressMonitor()).getContentStream()) {
                    byte[] bytes = in.readAllBytes();
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    out.write("\"WKB_BINARY_BASE64:" + escape(base64) + "\"");
                }
            } else {
                out.write("null");
            }
        } catch (Exception e) {
            out.write("null"); 
        }
    }

    private String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
