/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.planner.plan;

import io.confluent.ksql.execution.expression.tree.ColumnReferenceExp;
import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.execution.expression.tree.UnqualifiedColumnReferenceExp;
import io.confluent.ksql.execution.plan.SelectExpression;
import io.confluent.ksql.execution.util.ExpressionTypeManager;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.parser.tree.AllColumns;
import io.confluent.ksql.parser.tree.SelectItem;
import io.confluent.ksql.parser.tree.SingleColumn;
import io.confluent.ksql.schema.ksql.Column;
import io.confluent.ksql.schema.ksql.Column.Namespace;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.LogicalSchema.Builder;
import io.confluent.ksql.schema.ksql.types.SqlType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class SelectionUtil {

  private SelectionUtil() {
  }

  /*
   * The algorithm behind this method feels unnecessarily complicated and is begging
   * for someone to come along and improve it, but until that time here is
   * a description of what's going on.
   *
   * Essentially, we need to build a logical schema that mirrors the physical
   * schema until https://github.com/confluentinc/ksql/issues/6374 is addressed.
   * That means that the keys must be ordered in the same way as the parent schema
   * (e.g. if the source schema was K1 INT KEY, K2 INT KEY and the projection is
   * SELECT K2, K1 this method will produce an output schema that is K1, K2
   * despite the way that the keys were ordered in the projection) - see
   * https://github.com/confluentinc/ksql/pull/7477 for context on the bug.
   *
   * But we cannot simply select all the keys and then the values, we must maintain
   * the interleaving of key and values because transient queries return all columns
   * to the user as "value columns". If someone issues a SELECT VALUE, * FROM FOO
   * it is expected that VALUE shows up _before_ the key fields. This means we need to
   * reorder the key columns within the list of projections without affecting the
   * relative order the keys/values.
   *
   * To spice things up even further, there's the possibility that the same key is
   * aliased multiple times (SELECT K1 AS X, K2 AS Y FROM ...), which is not supported
   * but is verified later when building the final projection - so we maintain it here.
   *
   * Now on to the algorithm itself: we make two passes through the list of projections.
   * The first pass builds a mapping from source key to all the projections for that key.
   * We will use this mapping to sort the keys in the second pass. This mapping is two
   * dimensional to address the possibility of the same key with multiple aliases.
   *
   * The second pass goes through the list of projections again and builds the logical schema,
   * but this time if we encounter a projection that references a key column, we instead take
   * it from the list we built in the first pass (in order defined by the parent schema).
   */
  public static LogicalSchema buildProjectionSchema(
      final LogicalSchema parentSchema,
      final List<SelectExpression> projection,
      final FunctionRegistry functionRegistry
  ) {
    final ExpressionTypeManager expressionTypeManager = new ExpressionTypeManager(
        parentSchema,
        functionRegistry
    );

    // keyExpressions[i] represents the expressions found in projection
    // that are associated with parentSchema's key at index i
    final List<List<SelectExpression>> keyExpressions = new ArrayList<>(parentSchema.key().size());
    for (int i = 0; i < parentSchema.key().size(); i++) {
      keyExpressions.add(new ArrayList<>());
    }

    // first pass to construct keyExpressions, keyExpressionMembership
    // is just a convenience data structure so that we don't have to do
    // the isKey check in the second iteration below
    final Set<SelectExpression> keyExpressionMembership = new HashSet<>();
    for (final SelectExpression select : projection) {
      final Expression expression = select.getExpression();
      if (expression instanceof ColumnReferenceExp) {
        final ColumnName name = ((ColumnReferenceExp) expression).getColumnName();
        parentSchema.findColumn(name)
            .filter(c -> c.namespace() == Namespace.KEY)
            .ifPresent(c -> {
              keyExpressions.get(c.index()).add(select);
              keyExpressionMembership.add(select);
            });
      }
    }

    // second pass, which iterates the projections but ignores any key expressions,
    // instead taking them from the ordered keyExpressions list
    final Builder builder = LogicalSchema.builder();
    int currKeyIdx = 0;
    for (final SelectExpression select : projection) {
      if (keyExpressionMembership.contains(select)) {
        while (keyExpressions.get(currKeyIdx).isEmpty()) {
          currKeyIdx++;
        }
        final SelectExpression keyExp = keyExpressions.get(currKeyIdx).remove(0);
        final SqlType type = expressionTypeManager.getExpressionSqlType(keyExp.getExpression());
        builder.keyColumn(keyExp.getAlias(), type);
      } else {
        final Expression expression = select.getExpression();
        final SqlType type = expressionTypeManager.getExpressionSqlType(expression);
        if (type == null) {
          throw new IllegalArgumentException("Can't infer a type of null. Please explicitly cast "
              + "it to a required type, e.g. CAST(null AS VARCHAR).");
        }
        builder.valueColumn(select.getAlias(), type);
      }
    }

    return builder.build();
  }

  public static List<SelectExpression> buildSelectExpressions(
      final PlanNode parentNode,
      final List<? extends SelectItem> selectItems,
      final Optional<LogicalSchema> targetSchema
  ) {
    return IntStream.range(0, selectItems.size())
        .boxed()
        .flatMap(idx -> resolveSelectItem(idx, selectItems, parentNode, targetSchema))
        .collect(Collectors.toList());
  }

  private static Stream<SelectExpression> resolveSelectItem(
      final int idx,
      final List<? extends SelectItem> selectItems,
      final PlanNode parentNode,
      final Optional<LogicalSchema> targetSchema
  ) {
    final SelectItem selectItem = selectItems.get(idx);

    if (selectItem instanceof SingleColumn) {
      final SingleColumn column = (SingleColumn) selectItem;
      // if the column we are trying to coerce into a target schema is beyond
      // the target schema's max columns ignore it. this will generate a failure
      // down the line when we check that the result schema is identical to
      // the schema of the source we are attempting to fit
      final Optional<Column> targetColumn = targetSchema
          .filter(schema -> schema.columns().size() > idx)
          .map(schema -> schema.columns().get(idx));

      return resolveSingleColumn(idx, parentNode, column, targetColumn);
    }

    if (selectItem instanceof AllColumns) {
      return resolveAllColumns(parentNode, (AllColumns) selectItem);
    }

    throw new IllegalArgumentException(
        "Unsupported SelectItem type: " + selectItem.getClass().getName());
  }

  private static Stream<SelectExpression> resolveSingleColumn(
      final int idx,
      final PlanNode parentNode,
      final SingleColumn column,
      final Optional<Column> targetColumn
  ) {
    final Expression expression = parentNode.resolveSelect(idx, column.getExpression());
    final ColumnName alias = column.getAlias()
        .orElseThrow(() -> new IllegalStateException("Alias should be present by this point"));

    return Stream.of(SelectExpression.of(
        alias,
        targetColumn
            .map(col -> ImplicitlyCastResolver.resolve(expression, col.type()))
            .orElse(expression))
    );
  }

  private static Stream<SelectExpression> resolveAllColumns(
      final PlanNode parentNode,
      final AllColumns allColumns
  ) {
    final Stream<ColumnName> columns = parentNode
        .resolveSelectStar(allColumns.getSource());

    return columns
        .map(name -> SelectExpression.of(name, new UnqualifiedColumnReferenceExp(
            allColumns.getLocation(),
            name
        )));
  }
}
