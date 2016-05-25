package permissions

// This file was generated by the swagger tool.
// Editing this file might prove futile when you re-run the swagger generate command

import (
	"net/http"

	"github.com/go-swagger/go-swagger/httpkit"

	"permissions/models"
)

/*ListResourcePermissionsOK OK

swagger:response listResourcePermissionsOK
*/
type ListResourcePermissionsOK struct {

	// In: body
	Payload *models.PermissionList `json:"body,omitempty"`
}

// NewListResourcePermissionsOK creates ListResourcePermissionsOK with default headers values
func NewListResourcePermissionsOK() *ListResourcePermissionsOK {
	return &ListResourcePermissionsOK{}
}

// WithPayload adds the payload to the list resource permissions o k response
func (o *ListResourcePermissionsOK) WithPayload(payload *models.PermissionList) *ListResourcePermissionsOK {
	o.Payload = payload
	return o
}

// SetPayload sets the payload to the list resource permissions o k response
func (o *ListResourcePermissionsOK) SetPayload(payload *models.PermissionList) {
	o.Payload = payload
}

// WriteResponse to the client
func (o *ListResourcePermissionsOK) WriteResponse(rw http.ResponseWriter, producer httpkit.Producer) {

	rw.WriteHeader(200)
	if o.Payload != nil {
		if err := producer.Produce(rw, o.Payload); err != nil {
			panic(err) // let the recovery middleware deal with this
		}
	}
}

/*ListResourcePermissionsInternalServerError list resource permissions internal server error

swagger:response listResourcePermissionsInternalServerError
*/
type ListResourcePermissionsInternalServerError struct {

	// In: body
	Payload *models.ErrorOut `json:"body,omitempty"`
}

// NewListResourcePermissionsInternalServerError creates ListResourcePermissionsInternalServerError with default headers values
func NewListResourcePermissionsInternalServerError() *ListResourcePermissionsInternalServerError {
	return &ListResourcePermissionsInternalServerError{}
}

// WithPayload adds the payload to the list resource permissions internal server error response
func (o *ListResourcePermissionsInternalServerError) WithPayload(payload *models.ErrorOut) *ListResourcePermissionsInternalServerError {
	o.Payload = payload
	return o
}

// SetPayload sets the payload to the list resource permissions internal server error response
func (o *ListResourcePermissionsInternalServerError) SetPayload(payload *models.ErrorOut) {
	o.Payload = payload
}

// WriteResponse to the client
func (o *ListResourcePermissionsInternalServerError) WriteResponse(rw http.ResponseWriter, producer httpkit.Producer) {

	rw.WriteHeader(500)
	if o.Payload != nil {
		if err := producer.Produce(rw, o.Payload); err != nil {
			panic(err) // let the recovery middleware deal with this
		}
	}
}
