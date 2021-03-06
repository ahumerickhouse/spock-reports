package com.athaydes.spockframework.report.internal

import com.athaydes.spockframework.report.IReportCreator
import groovy.xml.MarkupBuilder
import org.spockframework.runtime.model.BlockInfo
import org.spockframework.runtime.model.ErrorInfo
import org.spockframework.runtime.model.FeatureInfo
import org.spockframework.runtime.model.IterationInfo

import static org.spockframework.runtime.model.BlockKind.*

/**
 *
 * User: Renato
 */
class HtmlReportCreator extends AbstractHtmlCreator<SpecData>
implements IReportCreator {

	def reportAggregator = HtmlReportAggregator.instance
	def stringFormatter = new StringFormatHelper()
	def problemWriter = new ProblemBlockWriter( stringFormatter: stringFormatter )

	final block2String = [
			( SETUP ): 'Given:',
			( CLEANUP ): 'Cleanup:',
			( THEN ): 'Then:',
			( EXPECT ): 'Expect:',
			( WHEN ): 'When:',
			( WHERE ): 'Where:',
			'AND': 'And:',
			'EXAMPLES': 'Examples:'
	]

	void setFeatureReportCss( String css ) {
		super.setCss( css )
	}

	void setSummaryReportCss( String css ) {
		reportAggregator?.css = css
	}

	@Override
	void createReportFor( SpecData data ) {
		def specClassName = data.info.description.className
		def reportsDir = createReportsDir()
		if ( reportsDir.exists() ) {
			try {
				new File( reportsDir, specClassName + '.html' )
						.write( reportFor( data ) )
			} catch ( e ) {
				e.printStackTrace()
				println "${this.class.name} failed to create report for $specClassName, Reason: $e"
			}

		} else {
			println "${this.class.name} cannot create output directory: ${reportsDir.absolutePath}"
		}
	}

	@Override
	protected String reportHeader( SpecData data ) {
		"Report for ${data.info.description.className}"
	}

	void writeSummary( MarkupBuilder builder, SpecData data ) {
		builder.div( 'class': 'summary-report' ) {
			h3 'Summary:'
			builder.div( 'class': 'date-test-ran', whenAndWho.whenAndWhoRanTest( stringFormatter ) )
			table( 'class': 'summary-table' ) {
				thead {
					th 'Executed features'
					th 'Failures'
					th 'Errors'
					th 'Skipped'
					th 'Success rate'
					th 'Time'
				}
				tbody {
					tr {
						def stats = stats( data )
						td stats.totalRuns
						td stats.failures
						td stats.errors
						td stats.skipped
						td stringFormatter.toPercentage( stats.successRate )
						td stringFormatter.toTimeDuration( stats.time )
						reportAggregator?.aggregateReport( data.info.description.className, stats, outputDir )
					}
				}
			}
		}
	}

	protected Map stats( SpecData data ) {
		def failures = data.featureRuns.count { it.failuresByIteration.values().any { !it.isEmpty() } }
		def errors = data.featureRuns.count { it.error }
		def skipped = data.info.allFeatures.count { it.skipped }
		def total = data.featureRuns.size()
		def successRate = successRate( total, ( errors + failures ).toInteger() )
		[ failures: failures, errors: errors, skipped: skipped, totalRuns: total,
				successRate: successRate, time: data.totalTime ]
	}

	protected void writeDetails( MarkupBuilder builder, SpecData data ) {
		builder.h3 "Features:"
		builder.table( 'class': 'features-table' ) {
			colgroup {
				col( 'class': 'block-kind-col' )
				col( 'class': 'block-text-col' )
			}
			tbody {
				writeFeature( builder, data )
			}
		}
	}

	private void writeFeature( MarkupBuilder builder, SpecData data ) {
		data.info.allFeatures.each { FeatureInfo feature ->
			def run = data.featureRuns.find { run -> run.feature == feature }
			writeFeatureDescription( builder, feature, run )
			feature.blocks.each { BlockInfo block ->
				writeBlock( builder, block, feature.skipped )
			}
			writeRun( builder, run )
			if ( run ) writeProblemBlock( builder, run )
		}
	}

	private void writeBlock( MarkupBuilder builder, BlockInfo block, boolean isIgnored ) {
		def trCssClassArg = ( isIgnored ? [ 'class': 'ignored' ] : null )

		if ( !isEmptyOrContainsOnlyEmptyStrings( block.texts ) )
			block.texts.eachWithIndex { blockText, index ->
				writeBlockRow( builder, trCssClassArg,
						( index == 0 ? block.kind : 'AND' ), blockText )
			}
		else
			writeBlockRow( builder, trCssClassArg, block.kind, '----' )
	}

	private writeBlockRow( MarkupBuilder builder, cssClass, blockKind, text ) {
		builder.tr( cssClass ) {
			writeBlockKindTd( builder, blockKind )
			td {
				div( 'class': 'block-text', text )
			}
		}
	}

	protected boolean isEmptyOrContainsOnlyEmptyStrings( List<String> strings ) {
		!strings || strings.every { it.trim() == '' }
	}

	private void writeBlockKindTd( MarkupBuilder builder, blockKindKey ) {
		builder.td {
			div( 'class': 'block-kind', block2String[ blockKindKey ] )
		}
	}

	private void writeRun( MarkupBuilder builder, FeatureRun run ) {
		if ( !run || !run.feature.parameterized ) return
		builder.tr {
			writeBlockKindTd( builder, 'EXAMPLES' )
			td {
				div( 'class': 'spec-examples' ) {
					table( 'class': 'ex-table' ) {
						thead {
							run.feature.parameterNames.each { param ->
								th( 'class': 'ex-header', param )
							}
						}
						tbody {
							run.failuresByIteration.each { iteration, errors ->
								writeIteration( builder, iteration, errors )
							}
						}
					}
				}
			}
			td {
				div( 'class': 'spec-status', iterationsResult( run ) )
			}
		}

	}

	private String iterationsResult( FeatureRun run ) {
		def totalRuns = run.failuresByIteration.size()
		def totalErrors = run.failuresByIteration.values().count { !it.empty }
		"${totalRuns - totalErrors}/${totalRuns} passed"
	}

	private void writeIteration( MarkupBuilder builder, IterationInfo iteration,
	                             List<ErrorInfo> errors ) {
		builder.tr( 'class': errors ? 'ex-fail' : 'ex-pass' ) {
			iteration.dataValues.each { value ->
				td( 'class': 'ex-value', value )
			}
			td( 'class': 'ex-result', iterationResult( errors ) )
		}
	}

	private String iterationResult( List<ErrorInfo> errors ) {
		errors ? 'FAIL' : 'OK'
	}

	private void writeFeatureDescription( MarkupBuilder builder, FeatureInfo feature, FeatureRun run ) {
		if ( !feature.skipped && !run ) return
		def additionalCssClass = feature.skipped ? ' ignored' :
			run.error ? ' error' :
				run.failuresByIteration.any { !it.value.isEmpty() } ? ' failure' : ''

		builder.tr {
			td( colspan: '10' ) {
				div( 'class': 'feature-description' + additionalCssClass, feature.name )
			}
		}
	}

	private void writeProblemBlock( MarkupBuilder builder, FeatureRun run ) {
		def isError = run.error != null
		def isFailure = run.failuresByIteration.values().any { !it.isEmpty() }
		if ( isError || isFailure )
			builder.tr {
				td( colspan: '10' ) {
					div( 'class': 'problem-description' ) {
						div( 'class': 'problem-header', 'The following problems occurred:' )
						div( 'class': 'problem-list' ) {
							problemWriter.writeProblems( builder, run, isError )
						}
					}
				}
			}
	}

}
