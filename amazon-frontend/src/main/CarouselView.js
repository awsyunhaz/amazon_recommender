import React, { Component } from "react";

import Container from "@material-ui/core/Container";
import Box from "@material-ui/core/Box";
import Typography from "@material-ui/core/Typography";
import { withStyles } from "@material-ui/core/styles";

import Product from "./Product";
import MyCarousel from "../util/MyCarousel";
import { useStyles } from "./css/MyStyle";
import { MAX_NUM_ITEMS_IN_CAROUSEL } from "../constants/index";

class CarouselView extends Component {
	render() {
		const { classes } = this.props;

		return (
			<React.Fragment>
				<div className={classes.heroContent}>
					<Container maxWidth="lg">
						<Typography component="h1" variant="h2" align="center" color="textPrimary" gutterBottom fontWeight="fontWeightBold" fontFamily="italic">
							{this.props.title}
						</Typography>
					</Container>
				</div>
				<Container className={classes.cardGrid} maxWidth="lg">
					<MyCarousel>
						{this.props.productList.slice(0, MAX_NUM_ITEMS_IN_CAROUSEL).map((product, index) => (
							<Box key={index} component="div" m={2} height="90%">
								<Product
									product={product}
									// loadGuess={this.props.loadGuess}
								/>
							</Box>
						))}
					</MyCarousel>
				</Container>
			</React.Fragment>
		);
	}
}

export default withStyles(useStyles)(CarouselView);
