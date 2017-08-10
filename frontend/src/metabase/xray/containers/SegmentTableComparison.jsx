import React, { Component } from 'react'
import { connect } from 'react-redux'
import _ from 'underscore'

import { fetchSegmentTableComparison } from 'metabase/xray/xray'
import { getComparison } from 'metabase/xray/selectors'

import LoadingAndErrorWrapper from 'metabase/components/LoadingAndErrorWrapper'
import XRayComparison from 'metabase/xray/components/XRayComparison'

const mapStateToProps = state => ({
    comparison: getComparison(state)
})

const mapDispatchToProps = {
    fetchSegmentTableComparison
}

class SegmentTableComparison extends Component {
    componentWillMount () {
        const { segmentId, tableId } = this.props.params
        this.props.fetchSegmentTableComparison(segmentId, tableId)
    }

    render () {
        const { comparison } = this.props
        return (
            <LoadingAndErrorWrapper
                loading={!comparison}
                noBackground
            >
                { () =>

                    <XRayComparison
                        fields={
                            _.sortBy(Object.values(comparison.constituents[0].constituents).map(c =>
                                c.field
                            ), 'distance')
                        }
                        comparisonFields={[
                            'distance',
                            'threshold',
                            'entropy',
                            'count',
                            'histogram',
                            'uniqueness',
                            'nil%',
                        ]}
                        comparison={comparison.comparison}
                        itemA={{
                            name: comparison.constituents[0].features.segment.name,
                            constituents: comparison.constituents[0].constituents,
                            itemType: 'segment',
                            id: comparison.constituents[0].features.segment.id,
                        }}
                        itemB={{
                            name: comparison.constituents[1].features.table.display_name,
                            constituents: comparison.constituents[1].constituents,
                            itemType: 'table',
                            id: comparison.constituents[0].features.table.id,
                        }}
                    />
                }
            </LoadingAndErrorWrapper>
        )
    }
}

export default connect(mapStateToProps, mapDispatchToProps)(SegmentTableComparison)
