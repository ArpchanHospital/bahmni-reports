package org.bahmni.reports.template;


import net.sf.dynamicreports.jasper.builder.JasperReportBuilder;
import net.sf.dynamicreports.report.builder.column.TextColumnBuilder;
import net.sf.dynamicreports.report.constant.PageType;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.bahmni.reports.BahmniReportsProperties;
import org.bahmni.reports.model.ConceptDetails;
import org.bahmni.reports.model.ProgramObsTemplateConfig;
import org.bahmni.reports.model.Report;
import org.bahmni.reports.model.UsingDatasource;
import org.bahmni.reports.util.CommonComponents;
import org.bahmni.reports.util.SqlUtil;
import org.bahmni.webclients.HttpClient;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.stringtemplate.v4.ST;

import java.net.URI;
import java.net.URLEncoder;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.sf.dynamicreports.report.builder.DynamicReports.col;
import static net.sf.dynamicreports.report.builder.DynamicReports.type;
import static org.bahmni.reports.util.FileReaderUtil.getFileContent;

@UsingDatasource("openmrs")
public class ProgramObsTemplate extends BaseReportTemplate<ProgramObsTemplateConfig> {
    private static final String ENCOUNTER_CREATE_DATE = "encounterCreateDate";
    public static final String UNKNOWN_CONCEPT_ATTRIBUTE_KEY = "Unknown Concept";
    private BahmniReportsProperties bahmniReportsProperties;
    private ProgramObsTemplateConfig reportConfig;

    public ProgramObsTemplate(BahmniReportsProperties bahmniReportsProperties) {
        this.bahmniReportsProperties = bahmniReportsProperties;
    }

    @Override
    public JasperReportBuilder build(Connection connection, JasperReportBuilder jasperReport, Report<ProgramObsTemplateConfig> report, String
            startDate, String endDate, List<AutoCloseable> resources, PageType pageType) {
        this.reportConfig = report.getConfig();
        CommonComponents.addTo(jasperReport, report, pageType);

        List<String> patientAttributes = getPatientAttributes();
        List<String> programAttributes = getProgramAttributes();
        List<String> programNames = reportConfig.getProgramNames();

        List<ConceptDetails> conceptDetails = fetchLeafConceptsFor(reportConfig.getTemplateName(), report);
        String formattedSql = getFormattedSql("sql/programObsTemplate.sql", conceptDetails, patientAttributes, startDate, endDate, programAttributes, programNames);
        buildColumns(jasperReport, patientAttributes, conceptDetails, programAttributes);

        return SqlUtil.executeReportWithStoredProc(jasperReport, connection, formattedSql);
    }

    private List<ConceptDetails> fetchLeafConceptsFor(String templateName, Report<ProgramObsTemplateConfig> report) {
        HttpClient httpClient = report.getHttpClient();
        try {
            String encodedTemplateName = URLEncoder.encode(templateName, "UTF-8");
            String url = bahmniReportsProperties.getOpenmrsRootUrl() + "/reference-data/leafConcepts?conceptName=" + encodedTemplateName;
            String response = httpClient.get(new URI(url));
            return new ObjectMapper().readValue(response, new TypeReference<List<ConceptDetails>>() {
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void buildColumns(JasperReportBuilder jasperReport, List<String> patientAttributes, List<ConceptDetails> conceptDetails, List<String> programAttributes) {
        TextColumnBuilder<String> patientColumn = col.column("Patient ID", "identifier", type.stringType());
        TextColumnBuilder<String> patientNameColumn = col.column("Patient Name", "patient_name", type.stringType());
        TextColumnBuilder<String> patientGenderColumn = col.column("Gender", "gender", type.stringType());
        TextColumnBuilder<String> patientAgeColumn = col.column("Age", "age", type.stringType());
        TextColumnBuilder<String> providerColumn = col.column("User", "provider_name", type.stringType());
        TextColumnBuilder<String> programName = col.column("Program Name", "program_name", type.stringType());
        TextColumnBuilder<String> dateEnrolled = col.column("Start Date", "date_enrolled", type.stringType());
        TextColumnBuilder<String> dateCompleted = col.column("Stop Date", "date_completed", type.stringType());
        TextColumnBuilder<String> encounterCreatedDateColumn = col.column("Encounter Created Date", "date_created", type.stringType());
        TextColumnBuilder<String> encounterDateTimeColumn = col.column("Encounter Date Time", "encounter_datetime", type.stringType());

        jasperReport.columns(patientColumn, patientNameColumn, patientGenderColumn, patientAgeColumn);
        for (String patientAttribute : patientAttributes) {
            TextColumnBuilder<String> column = col.column(patientAttribute, patientAttribute, type.stringType());
            jasperReport.addColumn(column);
        }

        for (String programAttribute : programAttributes) {
            TextColumnBuilder<String> column = col.column(programAttribute, programAttribute, type.stringType());
            jasperReport.addColumn(column);
        }

        jasperReport.columns(programName,dateEnrolled,dateCompleted,providerColumn, encounterDateTimeColumn, encounterCreatedDateColumn);
        for (ConceptDetails concept : conceptDetails) {
            TextColumnBuilder<String> column = col.column(concept.getName(), concept.getFullName(), type.stringType());
            jasperReport.addColumn(column);
        }
    }

    private String getFormattedSql(String sqlFilePath, List<ConceptDetails> conceptDetails, List<String> patientAttributes, String startDate, String endDate, List<String> programAttributes, List<String> programNames) {
        String locationTagNames = SqlUtil.toEscapedCommaSeparatedSqlString(reportConfig.getLocationTagNames());

        ST sqlTemplate = new ST(getFileContent(sqlFilePath), '#', '#');
        sqlTemplate.add("conceptNameInClauseEscapeQuote", getConceptNamesInClause(conceptDetails));
        sqlTemplate.add("programNamesListInClause", getProgramNamesListInClause(programNames));
        sqlTemplate.add("patientAttributesInClauseEscapeQuote", getAttributesInClause(patientAttributes));
        sqlTemplate.add("programAttributesInClauseEscapeQuote", getAttributesInClause(programAttributes));
        sqlTemplate.add("patientAttributes", constructPatientAttributeNamesString(patientAttributes));
        sqlTemplate.add("programAttributes", constructProgramAttributeNamesString(programAttributes));
        sqlTemplate.add("conceptNamesAndValue", constructConceptNamesString(conceptDetails));
        sqlTemplate.add("startDate", startDate);
        sqlTemplate.add("endDate", endDate);
        sqlTemplate.add("templateName", reportConfig.getTemplateName());
        sqlTemplate.add("applyDateRangeFor", applyDateRangeFor(reportConfig.getApplyDateRangeFor()));
        sqlTemplate.add("conceptSourceName", reportConfig.getConceptSource());

        if (StringUtils.isNotBlank(locationTagNames)) {
            String countOnlyTaggedLocationsJoin = String.format("INNER JOIN " +
                    "(SELECT DISTINCT location_id " +
                    "FROM location_tag_map INNER JOIN location_tag ON location_tag_map.location_tag_id = location_tag.location_tag_id " +
                    " AND location_tag.name IN (%s)) locations ON locations.location_id = e.location_id", locationTagNames);
            sqlTemplate.add("countOnlyTaggedLocationsJoin", countOnlyTaggedLocationsJoin);
        } else {
            sqlTemplate.add("countOnlyTaggedLocationsJoin", "");
        }
        return sqlTemplate.render();

    }

    private String getProgramNamesListInClause(List<String> programNames) {
        if (CollectionUtils.isEmpty(programNames)) {
            return "";
        }

        return String.format("AND prog.name IN (%s)", SqlUtil.toEscapedCommaSeparatedSqlString(programNames));
    }

    private String getConceptNamesInClause(List<ConceptDetails> conceptDetails) {
        List<String> conceptNames = new ArrayList<>();
        for (ConceptDetails conceptDetail : conceptDetails) {
            Map<String, Object> attributes = conceptDetail.getAttributes();
            if (attributes.containsKey(UNKNOWN_CONCEPT_ATTRIBUTE_KEY)) {
                conceptNames.add(encloseWithQuotes((String) attributes.get(UNKNOWN_CONCEPT_ATTRIBUTE_KEY)));
            }
            conceptNames.add(encloseWithQuotes(conceptDetail.getFullName()));
        }
        return escapeQuotes(StringUtils.join(conceptNames, ","));
    }

    private String encloseWithQuotes(String input) {
        return "'" + input + "'";
    }

    private String getAttributesInClause(List<String> parameters) {
        List<String> convertedList = new ArrayList<>();
        if (parameters.isEmpty()) {
            return "''";
        }
        for (String parameter : parameters) {
            convertedList.add(encloseWithQuotes(parameter));
        }
        return escapeQuotes(StringUtils.join(convertedList, ","));
    }

    private String applyDateRangeFor(String applyDateRangeFor) {
        if (applyDateRangeFor != null && applyDateRangeFor.equals(ENCOUNTER_CREATE_DATE)) {
            return "e.date_created";
        }
        return "e.encounter_datetime";
    }

    private String constructConceptNamesString(List<ConceptDetails> conceptDetailsList) {
        ArrayList<String> parts = new ArrayList<>();
        String helperString = "GROUP_CONCAT(DISTINCT(IF(cv.concept_full_name = %s, coalesce(o.code, o.value_numeric, o.value_boolean, o.value_text, o.value_datetime, o.concept_short_name, o.concept_full_name, o.date_created, o.encounter_datetime), %s)) SEPARATOR \\',\\') AS %s";;
        String unknownConceptClause = "IF(cv.concept_full_name = %s and o.concept_full_name = \\'true\\', \\'Unknown\\', null)";

        if (reportConfig.getConceptSource() == null) {
            helperString = "GROUP_CONCAT(DISTINCT(IF(cv.concept_full_name = %s, coalesce(o.value_numeric, o.value_boolean, o.value_text, o.value_datetime, o.concept_short_name, o.concept_full_name, o.date_created, o.encounter_datetime), %s)) SEPARATOR \\',\\') AS %s";
        }

        for (ConceptDetails conceptDetails : conceptDetailsList) {
            String conceptName = escapeQuotes(encloseWithQuotes(conceptDetails.getFullName()));
            String unknownValueConceptName = (String) conceptDetails.getAttribute(UNKNOWN_CONCEPT_ATTRIBUTE_KEY);
            String clauseText = String.format(helperString, conceptName, "NULL", conceptName);
            if(unknownValueConceptName != null) {
                String unknownConceptClauseString = String.format(unknownConceptClause, escapeQuotes(encloseWithQuotes(unknownValueConceptName)));
                clauseText = String.format(helperString, conceptName, unknownConceptClauseString, conceptName);
            }

            parts.add(clauseText);
        }

        return ", " + StringUtils.join(parts, ", ");
    }

    private String constructPatientAttributeNamesString(List<String> patientAttributes) {
        ArrayList<String> parts = new ArrayList<>();
        String helperString = "GROUP_CONCAT(DISTINCT(IF(o.patient_attr_name = \\'%s\\', o.patient_attr_value, NULL))) AS \\'%s\\'";

        for (String patientAttribute : patientAttributes) {
            parts.add(String.format(helperString, patientAttribute, patientAttribute));
        }

        return StringUtils.join(parts, ", ");
    }

    private String constructProgramAttributeNamesString(List<String> programAttributes) {
        ArrayList<String> parts = new ArrayList<>();
        String helperString = "GROUP_CONCAT(DISTINCT(IF(o.program_attr_name = \\'%s\\', o.program_attr_value, NULL))) AS \\'%s\\'";

        for (String programAttribute : programAttributes) {
            parts.add(String.format(helperString, programAttribute, programAttribute));
        }

        return StringUtils.join(parts, ", ");
    }

    private String escapeQuotes(String inclause) {
        return inclause.replace("'", "\\'");
    }

    private List<String> getPatientAttributes() {
        return reportConfig.getPatientAttributes() != null ? reportConfig.getPatientAttributes() : new ArrayList<String>();
    }

    private List<String> getProgramAttributes() {
        return reportConfig.getProgramAttributes() != null ? reportConfig.getProgramAttributes() : new ArrayList<String>();
    }
}