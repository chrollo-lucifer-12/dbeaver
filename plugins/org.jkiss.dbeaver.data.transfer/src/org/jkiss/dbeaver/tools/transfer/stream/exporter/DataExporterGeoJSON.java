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
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DataExporterGeoJSON extends StreamExporterAbstract implements IDocumentDataExporter {
    
    private static final Pattern WKT_POLYGON_PATTERN = Pattern.compile(
        "POLYGON\\s*\\(\\(([^)]+)\\)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WKT_POINT_PATTERN = Pattern.compile(
        "POINT\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WKT_LINESTRING_PATTERN = Pattern.compile(
        "LINESTRING\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WKT_MULTI_POLYGON_PATTERN = Pattern.compile(
        "MULTIPOLYGON\\s*\\(\\(([^)]+)\\)\\)", Pattern.CASE_INSENSITIVE);
    
    private DBDAttributeBinding[] columns;
    private int rowCount = 0;
    private String geometryColumnName;
    private boolean includeFeatureId;
    
    @Override
    public void init(IStreamDataExporterSite site) throws DBException {
        super.init(site);
        // Get configuration properties
        this.geometryColumnName = CommonUtils.toString(
            site.getProperties().get("geometryColumn"), "geometry");
        this.includeFeatureId = CommonUtils.getBoolean(
            site.getProperties().get("includeFeatureId"), true);
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
        out.write("  \"type\": \"FeatureCollection\",\n");
        out.write("  \"features\": [\n");
        rowCount = 0;
    }
    
    @Override
    public void exportRow(DBCSession session, DBCResultSet resultSet, Object[] row) throws DBException, IOException {
        PrintWriter out = getWriter();
        
        if (rowCount++ > 0) {
            out.write(",\n");
        }
        
        out.write("    {\n");
        out.write("      \"type\": \"Feature\",\n");
  
        if (includeFeatureId) {
            out.write("      \"id\": " + rowCount + ",\n");
        }
        
        out.write("      \"geometry\": ");
        writeGeometry(session, findGeometryValue(row));
        
     //   out.write(",\n      \"properties\": {\n");
        writeProperties(row);
        out.write("\n      }\n");
        out.write("    }");
    }
    
    @Override
    public void exportFooter(DBRProgressMonitor monitor) throws IOException {
        PrintWriter out = getWriter();
        out.write("\n  ]\n}");
    }
    
    private Object findGeometryValue(Object[] row) {
        for (int i = 0; i < columns.length; i++) {
            String columnName = columns[i].getLabel();
            if (geometryColumnName.equalsIgnoreCase(columnName) || 
                "geom".equalsIgnoreCase(columnName) ||
                "wkt".equalsIgnoreCase(columnName) ||
                "coordinates".equalsIgnoreCase(columnName)) {
                return row[i];
            }
        }
        return null;
    }
    
    private void writeGeometry(DBCSession session, Object value) throws IOException, DBException {
        PrintWriter out = getWriter();
        
        if (value == null) {
            out.write("null");
            return;
        }
        
        try {
            String geometryStr = null;
            
            if (value instanceof String) {
                geometryStr = ((String) value).trim();
            } else if (value instanceof byte[] bytes) {
                // Try to decode as WKB or convert to string
                try {
                    geometryStr = new String(bytes).trim();
                } catch (Exception e) {
                    // If it's binary WKB, encode as base64 for now
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    out.write("{\"type\": \"GeometryCollection\", \"geometries\": [], \"wkb_base64\": \"" + escape(base64) + "\"}");
                    return;
                }
            } else if (value instanceof DBDContent content) {
                try (InputStream in = content.getContents(session.getProgressMonitor()).getContentStream()) {
                    byte[] bytes = in.readAllBytes();
                    geometryStr = new String(bytes).trim();
                }
            } else {
                geometryStr = value.toString().trim();
            }
            
            // Parse WKT and convert to GeoJSON
            String geoJson = convertWktToGeoJson(geometryStr);
            if (geoJson != null) {
                out.write(geoJson);
            } else {
                out.write("null");
            }
            
        } catch (Exception e) {
            out.write("null");
        }
    }
    
    private String convertWktToGeoJson(String wkt) {
        if (wkt == null || wkt.isEmpty()) {
            return null;
        }
        
        Matcher polygonMatcher = WKT_POLYGON_PATTERN.matcher(wkt);
        if (polygonMatcher.find()) {
            String coordsStr = polygonMatcher.group(1);
            return convertPolygonToGeoJson(coordsStr);
        }
        
        // Handle POINT
        Matcher pointMatcher = WKT_POINT_PATTERN.matcher(wkt);
        if (pointMatcher.find()) {
            String coordsStr = pointMatcher.group(1);
            return convertPointToGeoJson(coordsStr);
        }
        
        // Handle LINESTRING
        Matcher lineMatcher = WKT_LINESTRING_PATTERN.matcher(wkt);
        if (lineMatcher.find()) {
            String coordsStr = lineMatcher.group(1);
            return convertLineStringToGeoJson(coordsStr);
        }

        Matcher  multipolygonMatcher = WKT_MULTI_POLYGON_PATTERN.matcher(wkt);
        if (multipolygonMatcher.find()) {
            String coordsStr = multipolygonMatcher.group(1);
            return convertMultiPolygonToGeoJson(coordsStr);
        }
        
        return null;
    }

    private String convertMultiPolygonToGeoJson(String coordsStr) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n        \"type\": \"MultiPolygon\",\n");
            json.append("        \"coordinates\": [[\n");
            
            String[] coords = coordsStr.split(",");
            for (int i = 0; i < coords.length; i++) {
                String[] lonLat = coords[i].trim().split("\\s+");
                if (lonLat.length >= 2) {
                    if (i > 0) json.append(",\n");
                    json.append("          [").append(lonLat[0]).append(", ").append(lonLat[1]).append("]");
                }
            }
            
            json.append("\n        ]]\n      }");
            return json.toString();
        } catch (Exception e) {
            return null;
        }
    }
    
    private String convertPolygonToGeoJson(String coordsStr) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n        \"type\": \"Polygon\",\n");
            json.append("        \"coordinates\": [[\n");
            
            String[] coords = coordsStr.split(",");
            for (int i = 0; i < coords.length; i++) {
                String[] lonLat = coords[i].trim().split("\\s+");
                if (lonLat.length >= 2) {
                    if (i > 0) json.append(",\n");
                    json.append("          [").append(lonLat[0]).append(", ").append(lonLat[1]).append("]");
                }
            }
            
            json.append("\n        ]]\n      }");
            return json.toString();
        } catch (Exception e) {
            return null;
        }
    }
    
    private String convertPointToGeoJson(String coordsStr) {
        try {
            String[] lonLat = coordsStr.trim().split("\\s+");
            if (lonLat.length >= 2) {
                return "{\n        \"type\": \"Point\",\n" +
                       "        \"coordinates\": [" + lonLat[0] + ", " + lonLat[1] + "]\n      }";
            }
        } catch (Exception e) {
            // Fall through
        }
        return null;
    }
    
    private String convertLineStringToGeoJson(String coordsStr) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n        \"type\": \"LineString\",\n");
            json.append("        \"coordinates\": [\n");
            
            String[] coords = coordsStr.split(",");
            for (int i = 0; i < coords.length; i++) {
                String[] lonLat = coords[i].trim().split("\\s+");
                if (lonLat.length >= 2) {
                    if (i > 0) json.append(",\n");
                    json.append("          [").append(lonLat[0]).append(", ").append(lonLat[1]).append("]");
                }
            }
            
            json.append("\n        ]\n      }");
            return json.toString();
        } catch (Exception e) {
            return null;
        }
    }
    
    private void writeProperties(Object[] row) throws IOException {
        PrintWriter out = getWriter();
        boolean first = true;
        
        for (int i = 0; i < columns.length; i++) {
            String columnName = columns[i].getLabel();
            
           
            if (geometryColumnName.equalsIgnoreCase(columnName) || 
                "geom".equalsIgnoreCase(columnName) ||
                "wkt".equalsIgnoreCase(columnName) ||
                "coordinates".equalsIgnoreCase(columnName)) {
                continue;
            }

            

            
            if (!first) {
                out.write(",\n");
            }
            
            first = false;
            
            
            out.write("        \"" + escape(columnName) + "\": ");
            
            Object value = row[i];
            if (value == null) {
                out.write("null");
            } else if (value instanceof Number) {
                out.write(value.toString());
            } else if (value instanceof Boolean) {
                out.write(value.toString());
            } else {
                out.write("\"" + escape(value.toString()) + "\"");
            }
        }
    }
    
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}