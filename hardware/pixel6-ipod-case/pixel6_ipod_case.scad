/*
 * Reclaimed Player removable iPod-style case for Google Pixel 6.
 *
 * Coordinate system: X = phone width, Y = phone height (USB-C end at 0),
 * Z = depth (back of case at 0). Dimensions are millimetres.
 *
 * Export one part at a time by setting `part` below to "cradle" or
 * "faceplate", rendering with F6, then exporting as STL. `print_plate`
 * arranges both pieces on a 180 x 180 mm bed for inspection.
 */

$fn = 72;
part = "print_plate"; // [cradle, faceplate, assembly, print_plate]

// Google Pixel 6 nominal envelope.
phone_width = 74.8;
phone_height = 158.6;
phone_depth = 8.9;
phone_corner_radius = 9.0;
// Fit-test revision: the Pixel's corners are substantially squarer than the
// nominal silhouette used for the cradle exterior. Keep this independent.
phone_cavity_corner_radius = 4.5;

// Tune these first after printing the fit coupons.
phone_clearance = 0.45; // radial XY clearance around the phone
cap_clearance = 0.30;   // radial clearance between cradle and faceplate

back_thickness = 2.0;
wall_thickness = 2.0;
rim_above_phone = 0.65;
face_thickness = 2.0;
skirt_thickness = 1.65;
skirt_depth = 3.8;
glass_gap = 0.20;

cradle_width = phone_width + 2 * (phone_clearance + wall_thickness);
cradle_height = phone_height + 2 * (phone_clearance + wall_thickness);
cradle_radius = phone_corner_radius + phone_clearance + wall_thickness;
cradle_depth = back_thickness + phone_depth + rim_above_phone;

cap_inner_width = cradle_width + 2 * cap_clearance;
cap_inner_height = cradle_height + 2 * cap_clearance;
face_width = cap_inner_width + 2 * skirt_thickness;
face_height = cap_inner_height + 2 * skirt_thickness;
face_radius = cradle_radius + cap_clearance + skirt_thickness;

// Apertures align with the current 47% screen / 53% wheel Classic UI.
// Positions are relative to the outer cradle, with Y=0 at the USB-C end.
screen_window_x = 6.35;
screen_window_y = 86.0;
screen_window_width = cradle_width - 2 * screen_window_x;
screen_window_height = 70.0;
screen_window_radius = 4.0;

wheel_center_x = cradle_width / 2;
wheel_opening_diameter = 48.0;
wheel_gap_below_screen = 15.0;
wheel_center_y = screen_window_y - wheel_gap_below_screen - wheel_opening_diameter / 2;
wheel_front_chamfer = 1.2;

// Broad reliefs are intentional: they tolerate normal device variation and
// avoid trapping heat around the camera bar and charging port.
camera_bump_top_offset = 10.25;
camera_bump_height = 22.0;
camera_window_margin = 2.0;
phone_top_y = wall_thickness + phone_clearance + phone_height;
camera_window_y = phone_top_y
    - camera_bump_top_offset
    - camera_bump_height
    - camera_window_margin;
camera_window_height = camera_bump_height + 2 * camera_window_margin;
usb_opening_width = 38.0;
right_button_opening_y = 54.0;
right_button_opening_height = 66.0;

module rounded_rect_prism(width, height, depth, radius) {
    linear_extrude(height = depth)
        hull() {
            for (x = [radius, width - radius])
                for (y = [radius, height - radius])
                    translate([x, y]) circle(r = radius);
        }
}

module rounded_rect_cut(width, height, depth, radius) {
    rounded_rect_prism(width, height, depth, min(radius, min(width, height) / 2));
}

module cradle() {
    difference() {
        rounded_rect_prism(cradle_width, cradle_height, cradle_depth, cradle_radius);

        // Phone cavity; it continues through the front so the display is open.
        translate([wall_thickness, wall_thickness, back_thickness])
            rounded_rect_prism(
                phone_width + 2 * phone_clearance,
                phone_height + 2 * phone_clearance,
                phone_depth + rim_above_phone + 1,
                phone_cavity_corner_radius
            );

        // Full-width camera-bar opening through the back plate.
        translate([-1, camera_window_y, -0.1])
            cube([cradle_width + 2, camera_window_height, back_thickness + 0.2]);

        // USB-C, speaker, and microphone access at the bottom edge.
        translate([(cradle_width - usb_opening_width) / 2, -0.1, back_thickness + 2.2])
            cube([usb_opening_width, wall_thickness + 0.2, cradle_depth]);

        // Power and volume buttons on the right edge.
        translate([cradle_width - wall_thickness - 0.1, right_button_opening_y, back_thickness + 2.0])
            cube([wall_thickness + 0.2, right_button_opening_height, cradle_depth]);

        // Top microphone relief.
        translate([cradle_width / 2 - 7, cradle_height - wall_thickness - 0.1, back_thickness + 2.2])
            cube([14, wall_thickness + 0.2, cradle_depth]);

        // Shallow side detents retain the removable faceplate.
        for (y = [35, cradle_height - 35]) {
            translate([-0.1, y - 4, cradle_depth - 2.45])
                cube([0.45, 8, 1.2]);
            translate([cradle_width - 0.35, y - 4, cradle_depth - 2.45])
                cube([0.45, 8, 1.2]);
        }
    }
}

module faceplate() {
    // Local origin matches the cradle in XY. The rear of the front panel is Z=0;
    // its retaining skirt extends toward negative Z.
    difference() {
        union() {
            // Front fascia.
            translate([-cap_clearance - skirt_thickness, -cap_clearance - skirt_thickness, 0])
                rounded_rect_prism(face_width, face_height, face_thickness, face_radius);

            // Perimeter skirt that slips over the cradle.
            difference() {
                translate([-cap_clearance - skirt_thickness, -cap_clearance - skirt_thickness, -skirt_depth])
                    rounded_rect_prism(face_width, face_height, skirt_depth, face_radius);
                translate([-cap_clearance, -cap_clearance, -skirt_depth - 0.1])
                    rounded_rect_prism(
                        cap_inner_width,
                        cap_inner_height,
                        skirt_depth + 0.2,
                        cradle_radius + cap_clearance
                    );
            }

            // Four small, printable snap nubs engage the cradle detents.
            for (y = [35, cradle_height - 35]) {
                translate([-cap_clearance, y - 3.5, -2.6])
                    cube([0.38, 7, 1.0]);
                translate([cradle_width + cap_clearance - 0.38, y - 3.5, -2.6])
                    cube([0.38, 7, 1.0]);
            }
        }

        // Rounded upper display window.
        translate([screen_window_x, screen_window_y, -0.1])
            rounded_rect_cut(
                screen_window_width,
                screen_window_height,
                face_thickness + 0.2,
                screen_window_radius
            );

        // Click Wheel opening, slightly flared at the front for finger comfort.
        translate([wheel_center_x, wheel_center_y, -0.1])
            cylinder(
                h = face_thickness + 0.2,
                d1 = wheel_opening_diameter,
                d2 = wheel_opening_diameter + 2 * wheel_front_chamfer
            );

        // Side thumb scallops make the cap easy to peel off intentionally.
        for (x = [-cap_clearance - skirt_thickness - 0.1,
                  cradle_width + cap_clearance + skirt_thickness + 0.1])
            translate([x, cradle_height / 2, -skirt_depth / 2])
                rotate([0, 90, 0])
                    cylinder(h = skirt_thickness + 0.4, d = 13, center = true);
    }
}

module assembly() {
    color([0.22, 0.22, 0.24]) cradle();
    color([0.88, 0.87, 0.82, 0.92])
        translate([0, 0, cradle_depth + glass_gap]) faceplate();
}

module faceplate_print() {
    // Put the broad front face at Z=0 with the retaining skirt upward.
    translate([
        cap_clearance + skirt_thickness,
        face_height - cap_clearance - skirt_thickness,
        face_thickness
    ])
        rotate([180, 0, 0]) faceplate();
}

module print_plate() {
    cradle();

    // Front face on the bed; the skirt and snap nubs print upward.
    translate([cradle_width + 15, 0, 0]) faceplate_print();
}

if (part == "cradle") cradle();
else if (part == "faceplate") faceplate_print();
else if (part == "assembly") assembly();
else print_plate();
