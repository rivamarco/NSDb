package io.radicalbit.nsdb.common.protocol

import io.radicalbit.nsdb.common.statement.SQLStatement

sealed trait EndpointInputProtocol

case class ExecuteSQLStatement(statement: SQLStatement)      extends EndpointInputProtocol
case class ShowMetrics(namespace: String)                    extends EndpointInputProtocol
case class DescribeMetric(namespace: String, metric: String) extends EndpointInputProtocol

sealed trait EndpointOutputProtocol

// TODO: the result must be well structured
// TODO: it must contain the schema (for both select and insert) and the final retrieved data (only for the select)
case class SQLStatementExecuted(namespace: String, metric: String, res: Seq[Bit] = Seq.empty)
    extends EndpointOutputProtocol

case class NamespaceMetricsListRetrieved(namespace: String, metrics: List[String])
case class MetricField(name: String, `type`: String)
case class MetricSchemaRetrieved(namespace: String, metric: String, fields: List[MetricField])
