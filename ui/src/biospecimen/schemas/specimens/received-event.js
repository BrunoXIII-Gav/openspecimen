export default {
  layout: {
    rows: [
      {
        fields: [ {
          name: "specimen.receivedEvent.receivedQuality",
          showWhen: "true == true"
        } ]
      },

      {
        fields: [ {
          name: "specimen.receivedEvent.receivedQtyDifferent",
          showWhen: "specimen.receivedEvent.receivedQuality && specimen.receivedEvent.receivedQuality != 'To be Received'"
        } ]
      },

      {
        fields: [ {
          name: "specimen.receivedEvent.receivedQty",
          showWhen: "specimen.receivedEvent.receivedQuality && specimen.receivedEvent.receivedQuality != 'To be Received' && specimen.receivedEvent.receivedQtyDifferent"
        } ]
      },

      {
        fields: [ {
          name: "specimen.receivedEvent.receivedQtyReason",
          showWhen: "specimen.receivedEvent.receivedQuality && specimen.receivedEvent.receivedQuality != 'To be Received' && specimen.receivedEvent.receivedQtyDifferent"
        } ]
      },

      {
        fields: [ {
          name: "specimen.receivedEvent.newLabel",
          type: "text",
          labelCode: "specimens.new_label",
          showWhen: "!wasReceived && allowSpmnRelabeling && specimen.receivedEvent.receivedQuality && specimen.receivedEvent.receivedQuality != 'To be Received'"
        } ]
      },

      {
        fields: [ {
          name: "specimen.receivedEvent.user",
          showWhen: "specimen.receivedEvent.receivedQuality && specimen.receivedEvent.receivedQuality != 'To be Received'"
        } ]
      },

      {
        fields: [ {
          name: "specimen.receivedEvent.time",
          showWhen: "specimen.receivedEvent.receivedQuality && specimen.receivedEvent.receivedQuality != 'To be Received'"
        } ]
      },

      {
        fields: [ {
          name: "specimen.receivedEvent.comments",
          showWhen: "specimen.receivedEvent.receivedQuality && specimen.receivedEvent.receivedQuality != 'To be Received'"
        } ]
      }
    ]
  }
}
