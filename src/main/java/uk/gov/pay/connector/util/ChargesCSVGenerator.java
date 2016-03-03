package uk.gov.pay.connector.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;

public class ChargesCSVGenerator {

    private static final String NEW_LINE_SEPARATOR = "\n";
    private static final Logger logger = LoggerFactory.getLogger(ChargesCSVGenerator.class);
    private static final String AMOUNT_KEY = "amount";
    public static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.00");

    // this ENUM is to map the database header fields to the header in the exported CSV file
    private enum ChargeHeaderEnum {
        REFERENCE("reference", "Service Payment Reference"),
        AMOUNT("amount", "Amount"),
        STATUS("status", "Status"),
        GATEWAY_TRANSACTION_ID("gateway_transaction_id", "Gateway Transaction ID"),
        CHARGE_ID("charge_id", "GOV.UK Pay ID"),
        DATE_CREATED("created_date", "Date Created");

        private String databaseHeader;
        private String csvHeader;

        ChargeHeaderEnum(String databaseHeader, String csvHeader) {
            this.databaseHeader = databaseHeader;
            this.csvHeader = csvHeader;
        }
    }

    public static String generate(List<Map<String, Object>> charges) {
        if (charges.isEmpty()) {
            return new String();
        }
        CSVFormat csvFileFormat = CSVFormat.DEFAULT.withRecordSeparator(NEW_LINE_SEPARATOR);
        StringBuilder builder = new StringBuilder();

        try (CSVPrinter csvPrinter = new CSVPrinter(builder, csvFileFormat)) {
            List<ChargeHeaderEnum> chargeHeaderEnums = printHeaders(csvPrinter);
            charges.forEach(charge -> {
                if (charge.get(AMOUNT_KEY) != null) {
                    double pounds = Double.valueOf(charge.get(AMOUNT_KEY).toString()) / 100;
                    charge.put(AMOUNT_KEY, DECIMAL_FORMAT.format(pounds));
                }
            });
            printRows(charges, csvPrinter, chargeHeaderEnums);
            csvPrinter.close();
        } catch (IOException e) {
            logger.error("Exception occurred while writing the file", e);
        }
        return builder.toString();
    }

    private static List<ChargeHeaderEnum> printHeaders(CSVPrinter csvPrinter) throws IOException {
        List<ChargeHeaderEnum> chargeHeaderEnums = Arrays.asList(ChargeHeaderEnum.values());
        List<Object> csvHeaders = chargeHeaderEnums.stream()
                .map(header -> (header.csvHeader))
                .collect(toList());
        csvPrinter.printRecord(csvHeaders); // prints the header
        return chargeHeaderEnums;
    }

    private static void printRows(List<Map<String, Object>> objectMapList, CSVPrinter csvPrinter, List<ChargeHeaderEnum> chargeHeaderEnums) throws IOException {
        for (Map<String, Object> charge : objectMapList) {
            List values = new ArrayList();
            for (ChargeHeaderEnum headerEnum : chargeHeaderEnums) {
                values.add(charge.get(headerEnum.databaseHeader));
            }
            csvPrinter.printRecord(values); //prints rows
        }
    }
}