package org.openkaryon.karyoforge.database.results;

import org.openkaryon.karyoforge.database.core.QueryPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A query owns one detached view containing its fetched pages. No SQL is issued here. */
public final class FetchedResults {
    private final QueryPage last;
    private final List<QueryPage.Row> fetched;

    private FetchedResults(QueryPage page, List<QueryPage.Row> fetched) {
        this.last = page;
        this.fetched = List.copyOf(fetched);
    }

    public static FetchedResults first(QueryPage page) {
        if (page.page() != 1 || page.totalRows() != page.rows().size()) throw new IllegalArgumentException("Invalid first result page.");
        verifyWidth(page);
        return new FetchedResults(page, page.rows());
    }

    public FetchedResults append(QueryPage next) {
        if (!last.hasNext() || !last.columns().equals(next.columns()) || next.page() != last.page() + 1
                || next.totalRows() != fetched.size() + next.rows().size()) {
            throw new IllegalArgumentException("The page does not continue the active result.");
        }
        verifyWidth(next);
        var rows = new ArrayList<>(fetched);
        rows.addAll(next.rows());
        return new FetchedResults(next, rows);
    }

    public QueryPage last() { return last; }
    public List<QueryPage.Column> columns() { return last.columns(); }
    public int fetchedCount() { return fetched.size(); }

    public View view(List<Filter> filters, List<Sort> sort) {
        if (filters.size() > 64 || sort.size() > 64) throw new IllegalArgumentException("Too many result options.");
        var predicates = new ArrayList<Predicate>();
        for (var filter : filters) {
            checkColumn(filter.column());
            var operand = filter.operand() == null ? null : ResultValue.parse(filter.operand(), filter.caseSensitive());
            predicates.add(new Predicate(filter, operand));
        }
        record Sorting(Sort key, List<ResultValue> values) {}
        var ordering = new ArrayList<Sorting>();
        for (var key : sort) {
            checkColumn(key.column());
            var parsed = new ArrayList<ResultValue>();
            for (var row : fetched) parsed.add(ResultValue.parse(row.cells().get(key.column()), true));
            ResultValue.checkTemporalForms(parsed);
            ordering.add(new Sorting(key, parsed));
        }
        var selected = new ArrayList<Integer>();
        for (int index = 0; index < fetched.size(); index++) {
            var row = fetched.get(index);
            if (predicates.stream().allMatch(predicate -> predicate.matches(row))) selected.add(index);
        }
        if (!ordering.isEmpty()) {
            selected.sort((first, second) -> {
                for (var order : ordering) {
                    var key = order.key();
                    ResultValue left = order.values().get(first);
                    ResultValue right = order.values().get(second);
                    if (left == null || right == null) {
                        if (left == right) continue;
                        return (left == null) == key.nullsFirst() ? -1 : 1;
                    }
                    int comparison = Integer.signum(left.compare(right));
                    if (comparison != 0) return key.descending() ? -comparison : comparison;
                }
                return Integer.compare(first, second);
            });
        }
        return new View(columns(), selected.stream().map(fetched::get).toList(), fetched.size());
    }

    private void checkColumn(int column) {
        if (column < 0 || column >= columns().size()) throw new IllegalArgumentException("Choose an existing result column.");
    }

    private static void verifyWidth(QueryPage page) {
        if (page.rows().stream().anyMatch(row -> row.cells().size() != page.columns().size())) {
            throw new IllegalArgumentException("Result row width differs from its columns.");
        }
    }

    public record View(List<QueryPage.Column> columns, List<QueryPage.Row> rows, int fetchedCount) {
        public View {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }

    public record Sort(int column, boolean descending, boolean nullsFirst) {}

    public enum Operator {
        CONTAINS("Contains"), EQUAL("Equals"), NOT_EQUAL("Does not equal"), LESS("Less than"),
        LESS_EQUAL("Less than or equal"), GREATER("Greater than"), GREATER_EQUAL("Greater than or equal"),
        IS_NULL("Is NULL"), NOT_NULL("Is not NULL");

        private final String label;
        Operator(String label) { this.label = label; }
        @Override public String toString() { return label; }
        public boolean needsValue() { return this != IS_NULL && this != NOT_NULL; }
    }

    public record Filter(int column, Operator operator, QueryPage.Cell operand, boolean caseSensitive) {
        public Filter {
            Objects.requireNonNull(operator);
            if (operator.needsValue() && (operand == null || operand.kind() == QueryPage.Cell.Kind.NULL || operand.clipped())
                    || !operator.needsValue() && operand != null) {
                throw new IllegalArgumentException("Provide a complete typed filter value, or choose a NULL operator.");
            }
            if (operand != null && (!caseSensitive || operator == Operator.CONTAINS) && !ResultValue.isText(operand.kind())) {
                throw new IllegalArgumentException("Contains and case folding require a text filter.");
            }
        }
    }

    private record Predicate(Filter filter, ResultValue operand) {
        private boolean matches(QueryPage.Row row) {
            var cell = row.cells().get(filter.column());
            var operator = filter.operator();
            if (operator == Operator.IS_NULL) return cell.kind() == QueryPage.Cell.Kind.NULL;
            if (operator == Operator.NOT_NULL) return cell.kind() != QueryPage.Cell.Kind.NULL;
            var value = ResultValue.parse(cell, filter.caseSensitive());
            if (value == null || value.domain() != operand.domain()) return false;
            if (operator == Operator.CONTAINS) return value.contains(operand);
            int compare = value.compare(operand);
            return switch (operator) {
                case EQUAL -> compare == 0;
                case NOT_EQUAL -> compare != 0;
                case LESS -> compare < 0;
                case LESS_EQUAL -> compare <= 0;
                case GREATER -> compare > 0;
                case GREATER_EQUAL -> compare >= 0;
                default -> throw new IllegalStateException("Unexpected filter operator.");
            };
        }
    }
}
