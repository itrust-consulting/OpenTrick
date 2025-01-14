package lu.itrust.business.ts.exportation.word.impl.docx4j.helper;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.docx4j.openpackaging.contenttype.ContentTypes;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.exceptions.InvalidFormatException;
import org.docx4j.openpackaging.packages.SpreadsheetMLPackage;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.openpackaging.parts.VMLPart;
import org.docx4j.openpackaging.parts.SpreadsheetML.Styles;
import org.docx4j.openpackaging.parts.SpreadsheetML.TablePart;
import org.docx4j.openpackaging.parts.SpreadsheetML.WorkbookPart;
import org.docx4j.openpackaging.parts.SpreadsheetML.WorksheetPart;
import org.docx4j.relationships.Relationship;
import org.springframework.util.StringUtils;
import org.xlsx4j.jaxb.Context;
import org.xlsx4j.org.apache.poi.ss.usermodel.DataFormatter;
import org.xlsx4j.sml.CTMergeCell;
import org.xlsx4j.sml.CTRst;
import org.xlsx4j.sml.CTSheetDimension;
import org.xlsx4j.sml.CTTable;
import org.xlsx4j.sml.CTTableColumn;
import org.xlsx4j.sml.CTTablePart;
import org.xlsx4j.sml.CTTableStyleInfo;
import org.xlsx4j.sml.CTXstringWhitespace;
import org.xlsx4j.sml.Cell;
import org.xlsx4j.sml.Row;
import org.xlsx4j.sml.STCellType;
import org.xlsx4j.sml.Sheet;
import org.xlsx4j.sml.SheetData;
import org.xlsx4j.sml.Worksheet;

import jakarta.xml.bind.JAXBException;

public final class ExcelHelper {

	private static final char ABSOLUTE_REFERENCE_MARKER = '$';

	public static final Pattern CELL_REF_PATTERN = Pattern.compile("(\\$?[A-Z]+)?(\\$?\\d+)?",
			Pattern.CASE_INSENSITIVE);

	public static final Pattern STRICTLY_CELL_REF_PATTERN = Pattern.compile("\\$?([A-Z]+)\\$?(\\d+)",
			Pattern.CASE_INSENSITIVE);

	private ExcelHelper() {
		throw new IllegalStateException("Utility class");
	}

	public static Cell setValue(Row row, int cellIndex, Object value) {
		Cell cell2 = getOrCreateCell(row, cellIndex);
		if (value instanceof Double)
			setValue(cell2, (Double) value);
		else if (value instanceof Integer)
			setValue(cell2, (Integer) value);
		else if (value instanceof Boolean)
			setValue(cell2, (Boolean) value);
		else
			setValue(cell2, value == null ? "" : String.valueOf(value));
		return cell2;
	}

	public static void setValuePercent(Row row, int cellIndex, double value) {
		Cell cell = getOrCreateCell(row, cellIndex);
		cell.setS(1L);
		cell.setV((value >= 1 ? value * 0.01 : value) + "");
	}

	public static void setValue(Cell cell, double value) {
		cell.setT(STCellType.N);
		cell.setV(value + "");
	}

	public static void setValue(Cell cell, int value) {
		cell.setT(STCellType.N);
		cell.setV(value + "");
	}

	public static void setValue(Cell cell, Boolean value) {
		cell.setT(STCellType.B);
		cell.setV(value != null && value ? "1" : "0");
	}

	public static Cell setFormula(Cell cell, String formula) {
		cell.setF(Context.getsmlObjectFactory().createCTCellFormula());
		cell.getF().setValue(formula);
		return cell;
	}

	public static Row getOrCreateRow(SheetData sheet, int index, int colSize) {
		Row sheetRow = getRow(sheet, index);
		if (sheetRow == null)
			sheetRow = createRow(sheet, index, colSize);
		else
			createCell(sheetRow, colSize);
		return sheetRow;
	}

	public static Cell getCell(Row row, int index) {
		if (index < 0)
			return null;
		if (row.getC().size() > index) {
			final Cell cell = row.getC().get(index);
			if (colToIndex(cell.getR(), index) == index)
				return cell;
		}
		return getCellAt(row, index);
	}

	public static Cell getOrCreateCell(Row row, int index) {
		Cell cell = getCell(row, index);
		if (cell == null)
			cell = createCell(row, index);
		return cell;
	}

	public static void setValue(Cell cell, String value) {
		if (value == null)
			value = "";
		CTXstringWhitespace ctXstringWhitespace = Context.getsmlObjectFactory().createCTXstringWhitespace();
		ctXstringWhitespace.setValue(value);
		CTRst ctRst = new CTRst();
		ctRst.setT(ctXstringWhitespace);
		cell.setIs(ctRst);
		cell.setT(STCellType.INLINE_STR);
	}

	public static Styles createStylesPart(WorksheetPart worksheetPart) throws InvalidFormatException {
		Styles part = new Styles();
		Relationship relationship = worksheetPart.getWorkbookPart().addTargetPart(part);
		part.setContents(Context.getsmlObjectFactory().createCTStylesheet());
		worksheetPart.getWorkbookPart().setPartShortcut(part, relationship.getType());
		return part;
	}

	/**
	 * Takes in a 0-based base-10 column and returns a ALPHA-26 representation. eg
	 * column #3 -> D
	 */
	public static String numToColString(int col) {
		// Excel counts column A as the 1st column, we
		// treat it as the 0th one
		int excelColNum = col + 1;

		StringBuilder colRef = new StringBuilder(2);
		int colRemain = excelColNum;

		while (colRemain > 0) {
			int thisPart = colRemain % 26;
			if (thisPart == 0) {
				thisPart = 26;
			}
			colRemain = (colRemain - thisPart) / 26;

			// The letter A is at 65
			char colChar = (char) (thisPart + 64);
			colRef.insert(0, colChar);
		}

		return colRef.toString();
	}

	/**
	 * takes in a column reference portion of a CellRef and converts it from
	 * ALPHA-26 number format to 0-based base 10. 'A' -> 0 'Z' -> 25 'AA' -> 26 'IV'
	 * -> 255
	 * 
	 * @return zero based column index
	 */
	public static int colStringToIndex(String ref) {
		int retval = 0;
		char[] refs = ref.toUpperCase(Locale.ROOT).toCharArray();
		for (int k = 0; k < refs.length; k++) {
			char thechar = refs[k];
			if (thechar == ABSOLUTE_REFERENCE_MARKER) {
				if (k != 0)
					throw new IllegalArgumentException("Bad col ref format '" + ref + "'");
			} else if (Character.isDigit(thechar))
				break;
			else
				// Character is uppercase letter, find relative value to A
				retval = (retval * 26) + (thechar - 'A' + 1);
		}
		return retval - 1;
	}

	/**
	 * 
	 * @param row1 >= 0
	 * @param col1 >= 0
	 * @param row2 >= 0
	 * @param col2 >= 0
	 * @return address
	 */
	public static String getAddress(int row1, int col1, int row2, int col2) {
		if (row1 < 0 || col1 < 0 || row2 < 0 || col2 < 0)
			throw new IllegalArgumentException("Invalid row or column");
		return String.format("%s%d:%s%d", numToColString(col1), (row1 + 1), numToColString(col2), (row2 + 1));
	}

	public static void mergeCell(Worksheet worksheet, int rowStart, int colStart, int rowEnd, int colEnd) {
		CTMergeCell mergeCell = Context.getsmlObjectFactory().createCTMergeCell();
		if (worksheet.getMergeCells() == null)
			worksheet.setMergeCells(Context.getsmlObjectFactory().createCTMergeCells());
		worksheet.getMergeCells().getMergeCell().add(mergeCell);
		mergeCell.setRef(getAddress(rowStart, colStart, rowEnd, colEnd));
	}

	public static WorksheetPart createWorkSheetPart(SpreadsheetMLPackage mlPackage, String name)
			throws Docx4JException, JAXBException {
		final int indexe = findNextSheetNumberAndId(mlPackage);
		final Set<Long> ids = mlPackage.getWorkbookPart().getContents().getSheets().getSheet().stream()
				.map(Sheet::getSheetId).collect(Collectors.toSet());
		final long id = getAvailableLong(ids);
		final WorksheetPart part = mlPackage.createWorksheetPart(
				new PartName(String.format("/xl/worksheets/sheet%d.xml", indexe)), name,
				id);
		part.getContents().getSheetData().setParent(part.getContents());
		part.getContents().setParent(part);
		return part;
	}

	public static TablePart createTablePart(WorksheetPart worksheetPart) throws Docx4JException {
		final var mlPackage = ((SpreadsheetMLPackage) worksheetPart.getPackage());
		final int indexe = findNextPartNumber("/xl/tables/table", ".xml", mlPackage);
		return createTablePart(worksheetPart, new PartName(String.format("/xl/tables/table%d.xml", indexe)),
				String.format("Table%d", indexe),
				getNextTableId(mlPackage));
	}

	public static long getNextTableId(final SpreadsheetMLPackage mlPackage) throws Docx4JException {
		return getAvailableLong(mlPackage.getWorkbookPart().getContents().getSheets().getSheet().stream().map(e -> {
			try {
				var sheetData = findSheet(mlPackage.getWorkbookPart(), e);
				if (sheetData == null)
					return Collections.emptyList();
				return findTable(sheetData);
			} catch (Exception ex) {
				return Collections.emptyList();
			}
		}).flatMap(e -> e.stream()).map(e -> {
			try {
				return ((TablePart) e).getContents().getId();
			} catch (Docx4JException e1) {
				return null;
			}
		}).filter(Objects::nonNull).collect(Collectors.toSet()));

	}

	public static CTTable createHeader(WorksheetPart worksheetPart, String name, String style, String[] columns,
			int length) throws Docx4JException {
		return createHeader(worksheetPart, name, style, columns, 0, length);
	}

	public static CTTable createHeader(WorksheetPart worksheetPart, String name, String style, String[] columns,
			int rowIndex, int length) throws Docx4JException {
		TablePart tablePart = createTablePart(worksheetPart);
		CTTable table = tablePart.getContents();
		if (style != null) {
			if (table.getTableStyleInfo() == null)
				table.setTableStyleInfo(new CTTableStyleInfo());
			table.getTableStyleInfo().setName(style);
		}

		Row row = getOrCreateRow(worksheetPart.getContents().getSheetData(), rowIndex, columns.length);
		for (int i = 0; i < columns.length; i++) {
			CTTableColumn column = new CTTableColumn();
			column.setId((long) i + 1);
			column.setName(columns[i]);
			table.getTableColumns().getTableColumn().add(column);
			setValue(row, i, columns[i]);
		}
		table.getTableColumns().setCount((long) columns.length);
		table.setRef(new AddressRef(new CellRef(rowIndex, 0),
				new CellRef(length == rowIndex ? rowIndex + 1 : rowIndex + length, columns.length - 1))
				.toString());
		table.getAutoFilter().setRef(table.getRef());
		worksheetPart.getContents().setDimension(new CTSheetDimension());
		worksheetPart.getContents().getDimension().setRef(table.getRef());
		if (!isEmpty(name))
			table.setDisplayName(name);
		return table;
	}

	public static boolean isEmpty(String name) {
		return name == null || name.length() == 0;
	}

	public static Row createRow(SheetData sheetData) {
		final long cellSize = sheetData.getRow().isEmpty() ? 1 : sheetData.getRow().get(0).getC().size();
		return createRow(sheetData, sheetData.getRow().size(), (int) cellSize);
	}

	public static Row createRow(SheetData sheetData, int cellSize) {
		return createRow(sheetData, sheetData.getRow().size(), cellSize);
	}

	public static WorksheetPart getWorksheetPart(Worksheet worksheet) {
		return (WorksheetPart) worksheet.getParent();
	}

	public static WorksheetPart getWorksheetPart(final SheetData sheetData) {
		return getWorksheetPart((Worksheet) sheetData.getParent());
	}

	public static WorksheetPart getWorksheetPart(final Row row) {
		return getWorksheetPart((SheetData) row.getParent());
	}

	public static WorksheetPart getWorksheetPart(final Cell cell) {
		return getWorksheetPart((Row) cell.getParent());
	}

	public static String getString(Cell cell, DataFormatter formatter) {
		if (cell == null)
			return "";
		switch (cell.getT()) {
			case INLINE_STR:
				if (cell.getIs() != null)
					return cell.getIs().getT().getValue();
				break;
			case S:
			case STR:
				if (cell.getV() == null)
					return null;
				break;
			case N:
				if (getWorksheetPart(cell).getWorkbookPart().getStylesPart() != null)
					break;
			case B:
				return cell.getV();
			case E:
				return "";
		}
		return formatter.formatCellValue(cell);
	}

	public static String getString(Row row, int cell, DataFormatter formatter) {
		return getString(row, cell, formatter, null);
	}

	public static Row getRow(SheetData sheet, int index) {
		if (index < 0)
			return null;
		if (sheet.getRow().size() > index) {
			final Row row = sheet.getRow().get(index);
			if (row.getR() != null && (row.getR() - 1) == index) {
				return row;
			}
		}
		
		for (int i = 0; i < sheet.getRow().size(); i++) {
			final Row row = sheet.getRow().get(i);
			if (row.getR() != null && (row.getR() - 1) == index) {
				return row;
			}
		}

		if (sheet.getRow().stream().allMatch(e -> e.getR() == null)) {
			for (int i = 0; i < sheet.getRow().size(); i++)
				sheet.getRow().get(i).setR(i + 1L);
			if (index >= 0 && sheet.getRow().size() > index)
				return sheet.getRow().get(index);
		}

		return null;
	}

	public static String getString(Row row, int cell, DataFormatter formatter, String defaultValue) {
		String value = getString(getCell(row, cell), formatter);
		return value == null ? defaultValue : value;
	}

	public static String getString(SheetData sheet, int row, int cell, DataFormatter formatter) {
		final Row r = getRow(sheet, row);
		return r == null ? null : getString(getRow(sheet, row), cell, formatter);
	}

	public static int colToIndex(String r, int index) {
		return StringUtils.hasText(r) ? colStringToIndex(r) : index;
	}

	public static double getDouble(Cell cell, DataFormatter formatter) {
		try {
			String value = getString(cell, formatter);
			if (isEmpty(value))
				return 0;
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static double getDouble(Row row, int index, DataFormatter formatter) {
		return getDouble(row, index, 0d, formatter);
	}

	public static double getDouble(Row row, int index, double defaultValue, DataFormatter formatter) {
		try {
			String value = getString(row, index, formatter);
			if (isEmpty(value))
				return defaultValue;
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	public static int getInt(Cell cell, DataFormatter formatter) {
		try {
			String value = getString(cell, formatter);
			if (isEmpty(value))
				return 0;
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static int getInt(Row row, int index, DataFormatter formatter) {
		try {
			String value = getString(row, index, formatter);
			if (isEmpty(value))
				return 0;
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static int getInt(Row row, int index, int defaultValue, DataFormatter formatter) {
		try {
			String value = getString(row, index, formatter);
			if (isEmpty(value))
				return defaultValue;
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	public static boolean getBoolean(Cell cell, DataFormatter formatter) {
		String value = getString(cell, formatter);
		try {
			return !(isEmpty(value) || Integer.parseInt(value) == 0);
		} catch (NumberFormatException e) {
			return Boolean.valueOf(value);
		}
	}

	public static boolean getBoolean(Row row, int index) {
		String value = getString(row, index, new DataFormatter());
		try {
			return !(isEmpty(value) || Integer.parseInt(value) == 0);
		} catch (NumberFormatException e) {
			return Boolean.valueOf(value);
		}
	}

	public static Worksheet findWorkSheet(WorkbookPart workbookPart, String name) throws Docx4JException {
		String id = workbookPart.getContents().getSheets().getSheet().parallelStream()
				.filter(s -> s.getName().equals(name)).map(Sheet::getId).findAny().orElse(null);
		if (isEmpty(id))
			return null;
		WorksheetPart worksheetPart = (WorksheetPart) workbookPart.getRelationshipsPart().getPart(id);
		return worksheetPart.getContents();
	}

	public static SheetData findSheet(WorkbookPart workbookPart, String name) throws Docx4JException {
		var worksheet = findWorkSheet(workbookPart, name);
		if (worksheet == null)
			return null;
		return worksheet.getSheetData();
	}

	public static SheetData findSheet(WorkbookPart workbookPart, Sheet sheet) throws Docx4JException {
		WorksheetPart worksheetPart = (WorksheetPart) workbookPart.getRelationshipsPart().getPart(sheet.getId());
		if (worksheetPart == null)
			return null;
		return worksheetPart.getContents().getSheetData();
	}

	public static Map<String, String> getSharedStrings(WorkbookPart workbookPart) throws Docx4JException {
		if (workbookPart.getSharedStrings() == null)
			return Collections.emptyMap();
		AtomicInteger integer = new AtomicInteger(0);
		return workbookPart.getSharedStrings().getContents().getSi().stream()
				.collect(Collectors.toMap(v -> integer.getAndIncrement() + "", v -> {
					if (v.getT() != null)
						return v.getT().getValue();
					else if (!v.getR().isEmpty())
						return v.getR().stream().map(r -> r.getT().getValue()).collect(Collectors.joining(""));
					else
						return v.getRPh().stream().map(r -> r.getT().getValue()).collect(Collectors.joining(""));
				}));
	}

	public static TablePart findTable(WorksheetPart worksheetPart, String name) throws Docx4JException {
		for (CTTablePart ctTablePart : worksheetPart.getContents().getTableParts().getTablePart()) {
			TablePart table = (TablePart) worksheetPart.getRelationshipsPart().getPart(ctTablePart.getId());
			if (table.getContents().getName().equals(name) || table.getContents().getDisplayName().equals(name))
				return table;
		}
		return null;
	}

	public static TablePart findTable(SheetData sheetData, String name) throws Docx4JException {
		final Worksheet worksheet = (Worksheet) sheetData.getParent();
		final WorksheetPart worksheetPart = (WorksheetPart) worksheet.getParent();
		if (worksheet.getTableParts() != null) {
			for (CTTablePart ctTablePart : worksheet.getTableParts().getTablePart()) {
				TablePart table = (TablePart) worksheetPart.getRelationshipsPart().getPart(ctTablePart.getId());
				if (table.getContents().getName().equals(name) || table.getContents().getDisplayName().equals(name))
					return table;
			}
		}
		return null;
	}

	public static List<TablePart> findTable(SheetData sheetData) {
		final Worksheet worksheet = (Worksheet) sheetData.getParent();
		final WorksheetPart worksheetPart = (WorksheetPart) worksheet.getParent();
		if (worksheet.getTableParts() != null) {
			return worksheet.getTableParts().getTablePart().stream()
					.map(e -> worksheetPart.getRelationshipsPart().getPart(e.getId()))
					.filter(TablePart.class::isInstance).map(TablePart.class::cast).collect(Collectors.toList());
		}
		return Collections.emptyList();
	}

	public static TablePart findTableNameStartWith(WorksheetPart worksheetPart, String name) throws Docx4JException {
		for (CTTablePart ctTablePart : worksheetPart.getContents().getTableParts().getTablePart()) {
			TablePart table = (TablePart) worksheetPart.getRelationshipsPart().getPart(ctTablePart.getId());
			if (table.getContents().getName().startsWith(name) || table.getContents().getDisplayName().startsWith(name))
				return table;
		}
		return null;
	}

	public static boolean applyHeaderAndFooter(String sheetSource, String sheetTarget, SpreadsheetMLPackage mlPackage)
			throws Docx4JException {
		final var sourceSheet = findWorkSheet(mlPackage.getWorkbookPart(), sheetSource);
		if (sourceSheet == null || sourceSheet.getHeaderFooter() == null)
			return false;
		final var targetSheet = findWorkSheet(mlPackage.getWorkbookPart(), sheetTarget);
		if (targetSheet == null || targetSheet.getHeaderFooter() != null)
			return false;
		targetSheet.setHeaderFooter(sourceSheet.getHeaderFooter());
		final var drawingHF = sourceSheet.getLegacyDrawingHF();
		if (drawingHF == null || targetSheet.getLegacyDrawingHF() != null)
			return false;
		final WorksheetPart worksheetPartSource = (WorksheetPart) sourceSheet.getParent();
		if (worksheetPartSource == null)
			return false;
		final var myDrawning = (VMLPart) worksheetPartSource.getRelationshipsPart().getPart(drawingHF.getId());
		if (myDrawning == null)
			return false;

		final var worksheetPartTarget = (WorksheetPart) targetSheet.getParent();

		final var vmlId = findNextPartNumber("/xl/drawings/vmlDrawing", ".vml", mlPackage);

		final VMLPart part = new VMLPart(new PartName(String.format("/xl/drawings/vmlDrawing%d.vml", vmlId)));

		part.setContents(myDrawning.getContents());

		final var id = worksheetPartTarget.addTargetPart(part).getId();

		final var relationships = myDrawning.getRelationshipsPart().getRelationships().getRelationship();

		for (var relationship : relationships) {
			final var myURL = URI.create(relationship.getTarget().replace("..", "/xl"));
			final var source = mlPackage.getParts().getParts().values().stream()
					.filter(p -> p.getPartName().getURI().compareTo(myURL) == 0).findAny().orElse(null);
			if (source != null) {
				var myRelationship = new Relationship();
				myRelationship.setType(source.getRelationshipType());
				myRelationship.setTarget(relationship.getTarget());
				myRelationship.setTargetMode(myRelationship.getTargetMode());
				part.getRelationshipsPart().addRelationship(myRelationship);
				source.getSourceRelationships().add(myRelationship);
			}
		}

		targetSheet.setLegacyDrawingHF(Context.getsmlObjectFactory().createCTLegacyDrawing());

		targetSheet.getLegacyDrawingHF().setId(id);

		return true;
	}

	/**
	 * It will retrieve an array of int size 2, int[2]<br>
	 * [0] Next sheet file name number: /xl/worksheets/sheet[0].xml<br>
	 * [1] The id of the sheet : rid[1]
	 * 
	 * @param mlPackage
	 * @return
	 */
	public static int findNextSheetNumberAndId(SpreadsheetMLPackage mlPackage) {
		return findNextPartNumber("/xl/worksheets/sheet", ".xml", mlPackage);
	}

	public static int findNextPartNumber(String path, String extension, SpreadsheetMLPackage mlPackage) {
		final var pattern = String.format("[%s]|[%s]", path, extension);
		final Set<Integer> ids = mlPackage.getParts().getParts().values().stream()
				.filter(p -> p.getPartName().getName().startsWith(path)).map(p -> {
					try {
						return Integer
								.parseInt(p.getPartName().getName().replaceAll(pattern, ""));
					} catch (NumberFormatException e) {
						return 0;
					}
				}).collect(Collectors.toSet());

		return getAvailableInteger(ids);
	}

	public static boolean isEmptyOrWhiteSpace(String value) {
		return isEmpty(value) || value.trim().length() == 0;
	}

	public static int getAvailableInteger(Set<Integer> ids) {
		if (ids.isEmpty())
			return 1;
		for (int i = 1; i < ids.size(); i++) {
			if (!ids.contains(i))
				return i;
		}
		return ids.size() + 1;
	}

	public static long getAvailableLong(Set<Long> ids) {
		if (ids.isEmpty())
			return 1;
		for (long i = 1; i < ids.size(); i++) {
			if (!ids.contains(i))
				return i;
		}
		for (long i = ids.size(); true; i++) {
			if (!ids.contains(i))
				return i;

		}
	}

	/**
	 * Returns the extension of the spreadsheetMLPackage based on its content type.
	 *
	 * @param spreadsheetMLPackage the SpreadsheetMLPackage to get the extension for
	 * @return the extension of the spreadsheetMLPackage as a String
	 */
	public static String getExtension(final SpreadsheetMLPackage spreadsheetMLPackage) {
		switch (spreadsheetMLPackage.getWorkbookPart().getContentType()) {
			case ContentTypes.SPREADSHEETML_WORKBOOK_MACROENABLED:
				return "xlsm";
			case ContentTypes.SPREADSHEETML_TEMPLATE_MACROENABLED:
				return "xltm";
			case ContentTypes.SPREADSHEETML_TEMPLATE:
				return "xltx";
			default:
				return "xlsx";
		}
	}

	private static Row createRow(SheetData sheet, int index, int colSize) {
		final Row sheetRow = Context.getsmlObjectFactory().createRow();
		final boolean hasRowIndex = sheet.getRow().isEmpty() || sheet.getRow().get(0).getR() != null;
		sheet.getRow().add(sheetRow);
		if (hasRowIndex) {
			sheetRow.setR(Long.valueOf(index + 1L));
			sheet.getRow().sort((e1, e2) -> {
				if (e1.getR() == null || e2.getR() == null)
					return 0;
				return Long.compare(e1.getR(), e2.getR());
			});
		}
		initialiseCells(sheetRow, colSize);
		return sheetRow;
	}

	private static void initialiseCells(Row sheetRow, int colSize) {
		if (sheetRow == null || sheetRow.getC().size() == colSize)
			return;
		if (sheetRow.getC().isEmpty()) {
			for (int i = 0; i < colSize; i++) {
				Cell cell = Context.getsmlObjectFactory().createCell();
				cell.setR(numToColString(i));
				sheetRow.getC().add(cell);
			}
		} else {
			for (int i = 0; i < colSize; i++) {
				Cell cell = getCell(sheetRow, i);
				if (cell == null) {
					cell = Context.getsmlObjectFactory().createCell();
					cell.setR(numToColString(i));
					sheetRow.getC().add(cell);
				}
			}
		}
	}

	private static Cell getCellAt(Row row, int index) {
		for (int i = 0; i < row.getC().size(); i++) {
			Cell cell = row.getC().get(i);
			if (colToIndex(cell.getR(), index) == index)
				return cell;
		}
		return null;
	}

	private static Cell createCell(Row row, int index) {
		Cell cell = getCell(row, index);
		if (cell == null) {
			cell = Context.getsmlObjectFactory().createCell();
			cell.setR(numToColString(index));
			row.getC().add(cell);
			row.getC().sort((c1, c2) -> {
				if (!(StringUtils.hasText(c1.getR()) || StringUtils.hasText(c2.getR())))
					return 0;
				return Integer.compare(colStringToIndex(c1.getR()), colStringToIndex(c2.getR()));
			});
		}
		return cell;
	}

	private static TablePart createTablePart(WorksheetPart worksheetPart, PartName partName, String name, long id)
			throws Docx4JException {
		CTTablePart tablePart = new CTTablePart();
		TablePart part = new TablePart(partName);
		Relationship r = worksheetPart.addTargetPart(part);
		CTTable table = Context.getsmlObjectFactory().createCTTable();
		table.setId(id);
		table.setName(name);
		table.setDisplayName(name);
		part.setContents(table);
		tablePart.setId(r.getId());
		table.setTotalsRowShown(true);
		table.setAutoFilter(Context.getsmlObjectFactory().createCTAutoFilter());
		table.setTableColumns(Context.getsmlObjectFactory().createCTTableColumns());
		table.setTableStyleInfo(Context.getsmlObjectFactory().createCTTableStyleInfo());
		table.getTableStyleInfo().setName("TableStyleMedium2");
		table.getTableStyleInfo().setShowRowStripes(true);
		worksheetPart.getContents().setTableParts(Context.getsmlObjectFactory().createCTTableParts());
		worksheetPart.getContents().getTableParts().getTablePart().add(tablePart);
		return part;
	}

}
