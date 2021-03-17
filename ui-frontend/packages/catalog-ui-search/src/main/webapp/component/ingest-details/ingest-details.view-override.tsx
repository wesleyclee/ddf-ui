/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
import React from 'react'
const Marionette = require('marionette')
const _ = require('underscore')
const $ = require('jquery')
const CustomElements = require('../../js/CustomElements.js')
const Dropzone = require('dropzone')
const UploadItemCollectionView = require('../upload-item/upload-item.collection.view.js')
const UploadBatchModel = require('../../js/model/UploadBatch.js')
const Common = require('../../js/Common.js')
const UploadSummary = require('../upload-summary/upload-summary.view.js')

function namespacedEvent(event, view) {
  return event + '.' + view.cid
}

function updateDropzoneHeight(view) {
  const filesHeight = view.$el.find('.details-files').height()
  const elementHeight = view.$el.height()
  view.$el
    .find('.details-dropzone')
    .css(
      'height',
      'calc(' +
        elementHeight +
        'px - ' +
        filesHeight +
        'px - 20px - 2.75rem' +
        ')'
    )
}

module.exports = Marionette.LayoutView.extend({
  template() {
    const addFiles = () => {}

    const newUpload = () => {
      this.$el.addClass('starting-new')
      setTimeout(() => {
        this.triggerNewUpload()
      }, 250)
    }

    const startUpload = () => {
      if (this.options.preIngestValidator) {
        this.options.preIngestValidator(
          _.bind(this.uploadBatchModel.start, this.uploadBatchModel)
        )
      } else {
        this.uploadBatchModel.start()
      }
    }

    const cancelUpload = () => this.uploadBatchModel.cancel()

    return (
      <React.Fragment>
        <div className="details-files">
          {/* <MRC
            view={UploadItemCollectionView}
            viewOptions={{ collection: this.uploadBatchModel.get('uploads') }}
          /> */}
        </div>
        <div className="details-dropzone">
          <div className="dropzone-text" onClick={() => addFiles()}>
            Drop files here or click to upload
          </div>
        </div>
        <div className="details-summary"></div>
        <div className="details-footer">
          <button
            data-id="Clearc"
            className="old-button footer-clear is-negative"
            onClick={() => newUpload()}
          >
            <span className="fa fa-times"></span>
            <span>Clear</span>
          </button>
          <button
            data-id="start"
            className="old-button footer-start is-positive"
            onClick={() => startUpload()}
          >
            <span className="fa fa-upload"></span>
            <span>Starterjelkj</span>
          </button>
          <button
            data-id="stop"
            className="old-button footer-cancel is-negative"
            onClick={() => cancelUpload()}
          >
            <span className="fa fa-stop"></span>
            <span>Stop</span>
          </button>
          <button
            data-id="new"
            className="old-button footer-new is-positive"
            onClick={() => newUpload()}
          >
            <span className="fa fa-upload"></span>
            <span>New</span>
          </button>
        </div>
      </React.Fragment>
    )
  },
  tagName: CustomElements.register('ingest-details'),
  regions: {
    files: '> .details-files',
    summary: '> .details-summary',
  },
  overrides: {},
  dropzone: undefined,
  uploadBatchModel: undefined,
  dropzoneAnimationRequestDetails: undefined,
  resetDropzone() {
    this.dropzone.options.autoProcessQueue = false
    this.dropzone.removeAllFiles(true)
  },
  triggerNewUpload() {
    this.onBeforeDestroy()
    this.render()
    this.onBeforeShow()
    this.resetDropzone()
  },
  onFirstRender() {
    this.setupDropzone()
  },
  onBeforeShow() {
    this.setupBatchModel()
    this.showFiles()
    this.showSummary()
    this.$el.removeClass()
    this.handleUploadUpdate()
  },
  setupBatchModel() {
    this.uploadBatchModel = new UploadBatchModel(
      {},
      {
        dropzone: this.dropzone,
      }
    )
    this.setupBatchModelListeners()
  },
  setupBatchModelListeners() {
    this.listenTo(
      this.uploadBatchModel,
      'add:uploads remove:uploads reset:uploads',
      this.handleUploadUpdate
    )
    this.listenTo(this.uploadBatchModel, 'change:sending', this.handleSending)
    this.listenTo(this.uploadBatchModel, 'change:finished', this.handleFinished)
  },
  handleFinished() {
    this.$el.toggleClass('is-finished', this.uploadBatchModel.get('finished'))
  },
  handleSending() {
    this.$el.toggleClass('is-sending', this.uploadBatchModel.get('sending'))
  },
  handleUploadUpdate() {
    if (
      this.uploadBatchModel.get('uploads').length === 0 &&
      !this.uploadBatchModel.get('sending')
    ) {
      Common.cancelRepaintForTimeframe(this.dropzoneAnimationRequestDetails)
      this.$el.toggleClass('has-files', false)
      this.unlistenToResize()
      this.$el.find('.details-dropzone').css('height', '')
    } else {
      this.$el.toggleClass('has-files', true)
      this.updateDropzoneHeight()
    }
  },
  setupDropzone() {
    const _this = this
    this.dropzone = new Dropzone(this.el.querySelector('.details-dropzone'), {
      paramName: 'parse.resource',
      url: this.options.url,
      maxFilesize: 5000000, //MB
      method: 'post',
      autoProcessQueue: false,
      headers: this.options.extraHeaders,
      sending(file, xhr, formData) {
        _.each(_this.overrides, (values, attribute) => {
          _.each(values, (value) => {
            formData.append('parse.' + attribute, value)
          })
        })
      },
    })
    if (this.options.handleUploadSuccess) {
      this.dropzone.on('success', this.options.handleUploadSuccess)
    }
  },
  showFiles() {
    this.files.show(
      new UploadItemCollectionView({
        collection: this.uploadBatchModel.get('uploads'),
      })
    )
  },
  showSummary() {
    this.summary.show(
      new UploadSummary({
        model: this.uploadBatchModel,
      })
    )
  },
  updateDropzoneHeight() {
    updateDropzoneHeight(this)
    this.listenToResize()
    Common.cancelRepaintForTimeframe(this.dropzoneAnimationRequestDetails)
    this.dropzoneAnimationRequestDetails = Common.repaintForTimeframe(
      2000,
      updateDropzoneHeight.bind(this, this)
    )
  },
  listenToResize() {
    $(window)
      .off(namespacedEvent('resize', this))
      .on(namespacedEvent('resize', this), this.updateDropzoneHeight.bind(this))
  },
  unlistenToResize() {
    $(window).off(namespacedEvent('resize', this))
  },
  onBeforeDestroy() {
    this.stopListening(this.uploadBatchModel)
    this.unlistenToResize()
  },
  setOverrides(json) {
    this.overrides = json
  },
})
