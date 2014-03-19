-- Populates the tool_request_status_codes table.

INSERT INTO tool_request_status_codes ( id, name, description ) VALUES
    ( '1FB4295B-684E-4657-AFAB-6CC0912312B1',
      'Submitted',
      'The request has been submitted, but not acted upon by the support team.' ),
    ( 'AFBBCDA8-49C3-47C0-9F28-DE87CBFBCBD6',
      'Pending',
      'The support team is waiting for a response from the requesting user.' ),
    ( 'B15FD4B9-A8D3-48EC-BD29-B0AACB51D335',
      'Evaluation',
      'The support team is evaluating the tool for installation.' ),
    ( '031D4F2C-3880-4483-88F8-E6C27C374340',
      'Installation',
      'The support team is installing the tool.' ),
    ( 'E4A0210C-663C-4943-BAE9-7D2FA7063301',
      'Validation',
      'The support team is verifying that the installation was successful.' ),
    ( '5ED94200-7565-45D8-B576-D7FF839E9993',
      'Completion',
      'The tool has been installed successfully.' ),
    ( '461F24EE-5521-461A-8C20-C400D912FB2D',
      'Failed',
      'The tool could not be installed.' );
